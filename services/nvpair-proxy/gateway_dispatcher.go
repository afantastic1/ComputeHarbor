// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"nvpair-shared/appdir"
	"nvpair-shared/modelselection"
	"nvpair-shared/schedulerwire"
)

type gatewayPolicy string

const (
	gatewayPolicyLocalOnly   gatewayPolicy = "local_only"
	gatewayPolicyCloudOnly   gatewayPolicy = "cloud_only"
	gatewayPolicyPreferLocal gatewayPolicy = "prefer_local"
)

type gatewayRoutingSettings struct {
	policy                        gatewayPolicy
	fallbackCloudModel            string
	cloudEnabled                  bool
	allowPaidFallback             bool
	monthlyBudgetUSD              float64
	perRequestMaxEstimatedCostUSD float64
	authorizedCloudNodes          map[string]string
}

type gatewayRequestTraits struct {
	model        string
	stream       bool
	capabilities map[string]bool
}

type gatewayDispatchError struct {
	status  int
	message string
	kind    string
}

type gatewayRoute struct {
	facade         *facade
	cloudTarget    cloudModelTarget
	provider       cloudProviderRuntime
	requestModel   string
	upstreamModel  string
	traits         gatewayRequestTraits
	remoteNode     *Node
	remoteFacade   *facade
	remotePeerUUID string
	localFallback  *gatewayRoute
}

type gatewayDispatcher struct {
	proxy               *Proxy
	cloudControlMu      sync.Mutex
	registry            *cloudRegistry
	client              *cloudHTTPClient
	settings            atomic.Pointer[gatewayRoutingSettings]
	auth                *gatewayAuthenticator
	budget              *cloudBudgetLedger
	budgetErr           error
	budgetOnce          sync.Once
	credentialMu        sync.RWMutex
	resolveCredential   func(string) (string, error)
	providerCredentials map[string]string
	remoteCloudMu       sync.Mutex
	remoteCloudExpires  time.Time
	remoteCloudCache    []remoteCloudModel
}

func newGatewayDispatcher(proxy *Proxy) *gatewayDispatcher {
	dispatcher := &gatewayDispatcher{
		proxy: proxy, registry: newCloudRegistry(), client: newCloudHTTPClient(cloudHTTPOptions{}),
		auth: newGatewayAuthenticator(),
	}
	dispatcher.settings.Store(&gatewayRoutingSettings{policy: gatewayPolicyLocalOnly})
	return dispatcher
}

func (d *gatewayDispatcher) cloudBudgetLedger() (*cloudBudgetLedger, error) {
	if d.budget != nil {
		return d.budget, nil
	}
	d.budgetOnce.Do(func() {
		budgetPath, err := appdir.Path("cloud-budget.json")
		if err == nil {
			d.budget, err = newCloudBudgetLedger(budgetPath)
		}
		d.budgetErr = err
	})
	return d.budget, d.budgetErr
}

func (d *gatewayDispatcher) modelDirectory() []gatewayModel {
	ids := make(map[string]gatewayModel)
	localNames := make(map[string]map[string]struct{})
	for _, f := range d.proxy.enabledFacades() {
		for _, node := range f.discovery.Nodes() {
			for _, model := range node.Models {
				if strings.TrimSpace(model) == "" {
					continue
				}
				if localNames[model] == nil {
					localNames[model] = make(map[string]struct{})
				}
				localNames[model][f.profile.Name] = struct{}{}
				id := localPublicModelID(f.profile.Name, model)
				ids[id] = gatewayModel{ID: id, Object: "model", OwnedBy: "pair"}
			}
		}
	}
	for model, engines := range localNames {
		if len(engines) == 1 {
			ids[model] = gatewayModel{ID: model, Object: "model", OwnedBy: "pair"}
		}
	}
	settings := d.settings.Load()
	if settings.cloudEnabled {
		for _, model := range d.registry.Snapshot().Models() {
			ids[model.PublicID] = gatewayModel{ID: model.PublicID, Object: "model", OwnedBy: "pair"}
		}
	}
	if settings.cloudEnabled {
		for _, model := range d.proxy.remoteCloudModels() {
			ids[model.ID] = gatewayModel{ID: model.ID, Object: "model", OwnedBy: "pair-remote-cloud"}
		}
	}
	for _, alias := range autoAliases {
		ids[alias] = gatewayModel{ID: alias, Object: "model", OwnedBy: "pair"}
	}
	models := make([]gatewayModel, 0, len(ids))
	for _, model := range ids {
		models = append(models, model)
	}
	sort.Slice(models, func(i, j int) bool { return models[i].ID < models[j].ID })
	return models
}

func (d *gatewayDispatcher) dispatch(w http.ResponseWriter, r *http.Request, body []byte, model string) {
	if d.requiresCloudAuthorization(model) && !d.auth.authorized(r.Header.Get("Authorization")) {
		writeGatewayError(w, http.StatusUnauthorized, "a valid Gateway bearer token is required", "unauthorized")
		return
	}
	route, dispatchErr := d.resolve(model, body)
	if dispatchErr != nil {
		writeGatewayError(w, dispatchErr.status, dispatchErr.message, dispatchErr.kind)
		return
	}
	if route.facade != nil {
		forwardBody, err := rewriteGatewayModel(body, route.upstreamModel)
		if err != nil {
			writeGatewayError(w, http.StatusBadRequest, "chat request is invalid", "invalid_request_error")
			return
		}
		r.Body = io.NopCloser(bytes.NewReader(forwardBody))
		r.ContentLength = int64(len(forwardBody))
		route.facade.handleHTTP(w, r)
		return
	}
	if route.remoteNode != nil {
		d.dispatchRemoteCloud(w, r, body, route)
		return
	}
	d.dispatchCloud(w, r, body, route)
}

func isAuthorizedCloudNode(settings *gatewayRoutingSettings, peerUUID string, certDER []byte) bool {
	if peerUUID == "" || len(certDER) == 0 {
		return false
	}
	fingerprint, ok := settings.authorizedCloudNodes[strings.ToLower(peerUUID)]
	return ok && fingerprint == cloudCertificateFingerprint(certDER)
}

func (d *gatewayDispatcher) peerCloudModelDirectory() any {
	settings := d.settings.Load()
	models := make([]map[string]any, 0)
	if settings.cloudEnabled {
		for _, model := range d.registry.Snapshot().Models() {
			capabilities := make([]string, 0, len(model.Capabilities))
			for capability, enabled := range model.Capabilities {
				if enabled {
					capabilities = append(capabilities, capability)
				}
			}
			sort.Strings(capabilities)
			models = append(models, map[string]any{"id": model.PublicID, "capabilities": capabilities})
		}
	}
	return map[string]any{"enabled": settings.cloudEnabled, "models": models}
}

func (d *gatewayDispatcher) serveAuthorizedCloudPeer(w http.ResponseWriter, r *http.Request, peerUUID string, certDER []byte) {
	settings := d.settings.Load()
	if !settings.cloudEnabled || !isAuthorizedCloudNode(settings, peerUUID, certDER) {
		writeGatewayError(w, http.StatusForbidden, "this paired node is not authorized to use cloud providers", "cloud_not_allowed")
		return
	}
	body, model, bodyErr := bufferBodyAndModel(w, r)
	if bodyErr != nil {
		writeRequestBodyError(w, bodyErr)
		return
	}
	if !strings.HasPrefix(model, "cloud/") {
		writeGatewayError(w, http.StatusForbidden, "terminal cloud requests require an explicit cloud model", "cloud_not_allowed")
		return
	}
	requestContext, cancel := context.WithCancel(r.Context())
	if d.proxy.mesh != nil {
		done := make(chan struct{})
		go d.monitorAuthorizedCloudPeer(requestContext, cancel, done, r, peerUUID, certDER, model)
		defer close(done)
	}
	defer cancel()
	r = r.WithContext(requestContext)
	if requestContext.Err() != nil {
		writeGatewayError(w, http.StatusForbidden, "paired Cloud authorization was revoked", "cloud_not_allowed")
		return
	}
	traits, err := parseGatewayRequestTraits(body)
	if err != nil {
		writeGatewayError(w, http.StatusBadRequest, "request body must be a valid chat completion object", "invalid_request_error")
		return
	}
	traits.model = model
	route, dispatchErr := d.resolveCloud(d.registry.Snapshot(), model, model, traits, settings)
	if dispatchErr != nil {
		writeGatewayError(w, dispatchErr.status, dispatchErr.message, dispatchErr.kind)
		return
	}
	d.dispatchCloudForCaller(w, r, body, route, peerUUID)
}

func (d *gatewayDispatcher) monitorAuthorizedCloudPeer(
	ctx context.Context,
	cancel context.CancelFunc,
	done <-chan struct{},
	request *http.Request,
	peerUUID string,
	certDER []byte,
	model string,
) {
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-done:
			return
		case <-ticker.C:
			if d.proxy.mesh == nil {
				cancel()
				return
			}
			d.proxy.mesh.Refresh()
			settings := d.settings.Load()
			_, stillPinned := d.proxy.mesh.VerifyClientPin(request)
			_, modelStillEnabled := d.registry.Snapshot().Resolve(model)
			if !stillPinned || !settings.cloudEnabled || !isAuthorizedCloudNode(settings, peerUUID, certDER) || !modelStillEnabled {
				cancel()
				return
			}
		}
	}
}

func (d *gatewayDispatcher) requiresCloudAuthorization(model string) bool {
	settings := d.settings.Load()
	if !settings.cloudEnabled {
		return false
	}
	if strings.HasPrefix(model, "cloud/") {
		return true
	}
	switch model {
	case AUTO_CLOUD_ALIAS:
		return settings.policy != gatewayPolicyLocalOnly
	case AUTO_LOCAL_ALIAS:
		return false
	default:
		return isGatewayAutoAlias(model) &&
			(settings.policy == gatewayPolicyCloudOnly || settings.policy == gatewayPolicyPreferLocal && settings.allowPaidFallback)
	}
}

func (d *gatewayDispatcher) authorizeModelDirectory(authorization string) bool {
	return !d.settings.Load().cloudEnabled || d.auth.authorized(authorization)
}

func (d *gatewayDispatcher) setClientToken(token string) {
	d.auth.setToken(token)
}

func (d *gatewayDispatcher) resolve(model string, body []byte) (gatewayRoute, *gatewayDispatchError) {
	traits, err := parseGatewayRequestTraits(body)
	if err != nil {
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusBadRequest, message: "request body must be a valid chat completion object", kind: "invalid_request_error"}
	}
	traits.model = model
	settings := d.settings.Load()
	if isGatewayAutoAlias(model) {
		if model == AUTO_CLOUD_ALIAS {
			if settings.policy == gatewayPolicyLocalOnly {
				return gatewayRoute{}, cloudNotAllowedError()
			}
			return d.resolveConfiguredCloud(settings, traits)
		}
		if model == AUTO_LOCAL_ALIAS {
			if settings.policy == gatewayPolicyCloudOnly {
				return gatewayRoute{}, cloudNotAllowedError()
			}
			return d.resolveLocalAuto(AUTO_BALANCED_ALIAS, traits, body)
		}
		return d.resolveAuto(settings, traits, body)
	}
	if settings.policy == gatewayPolicyLocalOnly && strings.HasPrefix(model, "cloud/") {
		return gatewayRoute{}, cloudNotAllowedError()
	}
	if settings.policy == gatewayPolicyCloudOnly && !strings.HasPrefix(model, "cloud/") {
		return gatewayRoute{}, cloudNotAllowedError()
	}
	if strings.HasPrefix(model, "cloud/") {
		return d.resolveCloud(d.registry.Snapshot(), model, model, traits, settings)
	}
	return d.resolveLocal(model, model, traits)
}

func (d *gatewayDispatcher) resolveAuto(
	settings *gatewayRoutingSettings,
	traits gatewayRequestTraits,
	body []byte,
) (gatewayRoute, *gatewayDispatchError) {
	switch settings.policy {
	case gatewayPolicyCloudOnly:
		return d.resolveConfiguredCloud(settings, traits)
	case gatewayPolicyPreferLocal:
		inventory := d.proxy.gatewayInventory()
		capacity := classifyLocalCapacity(traits.model, traits.capabilities, inventory)
		slog.Debug("Gateway classified local model capacity", "reason", capacity.reason, "model", traits.model)
		localRoute, localErr := d.resolveLocalAutoFromInventory(traits.model, traits, inventory)
		if capacity.reason == localCapacitySaturated && settings.allowPaidFallback && settings.cloudEnabled {
			cloudRoute, cloudErr := d.resolveConfiguredCloud(settings, traits)
			if cloudErr != nil {
				slog.Debug("Gateway kept saturated local route because configured Cloud fallback is unavailable", "reason", cloudErr.kind)
				if localErr != nil {
					return gatewayRoute{}, localErr
				}
				return localRoute, localErr
			}
			cloudRoute.localFallback = &localRoute
			return cloudRoute, nil
		}
		if capacity.reason == localCapacityUnavailable && settings.allowPaidFallback && settings.cloudEnabled {
			return d.resolveConfiguredCloud(settings, traits)
		}
		return localRoute, localErr
	default:
		localRoute, localErr := d.resolveLocalAuto(traits.model, traits, body)
		if localErr == nil {
			return localRoute, nil
		}
		return gatewayRoute{}, localErr
	}
}

func (d *gatewayDispatcher) resolveLocalAuto(
	alias string,
	traits gatewayRequestTraits,
	body []byte,
) (gatewayRoute, *gatewayDispatchError) {
	return d.resolveLocalAutoFromInventory(alias, traits, d.proxy.gatewayInventory())
}

func (d *gatewayDispatcher) resolveLocalAutoFromInventory(
	alias string,
	traits gatewayRequestTraits,
	inventory []modelselection.RuntimeModel,
) (gatewayRoute, *gatewayDispatchError) {
	selection := (modelselection.AutoModelSelector{}).Select(alias, modelselection.Requirements{
		Capabilities: traits.capabilities,
	}, inventory)
	if selection == nil {
		return gatewayRoute{}, noCompatibleLocalModel(inventory, traits.capabilities)
	}
	return gatewayRoute{
		facade: d.proxy.facadeFor(selection.Engine), requestModel: alias,
		upstreamModel: selection.Model.EngineModelID, traits: traits,
	}, nil
}

type localCapacityReason string

const (
	localCapacityAvailable   localCapacityReason = "local_available"
	localCapacitySaturated   localCapacityReason = "local_saturated"
	localCapacityUnavailable localCapacityReason = "no_local_capacity"
	localCapacityUnknown     localCapacityReason = "telemetry_unknown"
)

type localCapacityClassification struct {
	reason localCapacityReason
}

func classifyLocalCapacity(
	alias string,
	capabilities map[string]bool,
	inventory []modelselection.RuntimeModel,
) localCapacityClassification {
	selector := modelselection.AutoModelSelector{}
	requirements := modelselection.Requirements{Capabilities: capabilities}
	compatible := make([]modelselection.RuntimeModel, 0, len(inventory))
	for _, candidate := range inventory {
		if selector.Select(alias, requirements, []modelselection.RuntimeModel{candidate}) != nil {
			compatible = append(compatible, candidate)
		}
	}
	if len(compatible) == 0 {
		return localCapacityClassification{reason: localCapacityUnavailable}
	}

	allBusy := true
	unknown := false
	for _, candidate := range compatible {
		if !candidate.GPUPressureKnown {
			allBusy = false
			unknown = true
			continue
		}
		if candidate.GPUPressure < schedulerwire.MaxGPUPressure {
			allBusy = false
		}
	}
	if allBusy {
		return localCapacityClassification{reason: localCapacitySaturated}
	}
	if unknown {
		return localCapacityClassification{reason: localCapacityUnknown}
	}
	return localCapacityClassification{reason: localCapacityAvailable}
}

func noCompatibleLocalModel(inventory []modelselection.RuntimeModel, capabilities map[string]bool) *gatewayDispatchError {
	if len(inventory) > 0 && hasOptionalRequirement(capabilities) {
		return &gatewayDispatchError{status: http.StatusUnprocessableEntity, message: "no local model supports the requested capabilities", kind: "unsupported_capability"}
	}
	return &gatewayDispatchError{status: http.StatusNotFound, message: "no available model matches the requested auto policy", kind: "model_not_found"}
}

func hasOptionalRequirement(capabilities map[string]bool) bool {
	for capability, required := range capabilities {
		if required && capability != "chat" {
			return true
		}
	}
	return false
}

func (d *gatewayDispatcher) resolveConfiguredCloud(
	settings *gatewayRoutingSettings,
	traits gatewayRequestTraits,
) (gatewayRoute, *gatewayDispatchError) {
	if !settings.cloudEnabled {
		return gatewayRoute{}, cloudNotAllowedError()
	}
	if settings.fallbackCloudModel == "" {
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "no fallback cloud model is configured", kind: "model_not_found"}
	}
	snapshot := d.registry.Snapshot()
	if _, ok := snapshot.Resolve(settings.fallbackCloudModel); !ok {
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "configured fallback cloud model is unavailable", kind: "model_not_found"}
	}
	return d.resolveCloud(snapshot, settings.fallbackCloudModel, traits.model, traits, settings)
}

func (d *gatewayDispatcher) resolveCloud(
	snapshot *cloudRegistrySnapshot,
	publicID, requestModel string,
	traits gatewayRequestTraits,
	settings *gatewayRoutingSettings,
) (gatewayRoute, *gatewayDispatchError) {
	if !settings.cloudEnabled {
		return gatewayRoute{}, cloudNotAllowedError()
	}
	target, ok := snapshot.Resolve(publicID)
	if !ok {
		if remote, available := d.proxy.remoteCloudTarget(publicID); available {
			if missing := missingGatewayCapabilities(remote.Capabilities, traits.capabilities); len(missing) > 0 {
				return gatewayRoute{}, unsupportedGatewayCapabilities(missing)
			}
			return gatewayRoute{
				requestModel: requestModel, traits: traits,
				remoteNode: &remote.Node, remoteFacade: remote.Facade, remotePeerUUID: remote.Node.ClusterUUID,
			}, nil
		}
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "model is unavailable", kind: "model_not_found"}
	}
	if missing := missingGatewayCapabilities(target.Capabilities, traits.capabilities); len(missing) > 0 {
		return gatewayRoute{}, unsupportedGatewayCapabilities(missing)
	}
	provider, ok := snapshot.provider(target.ProviderID)
	if !ok {
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "model is unavailable", kind: "model_not_found"}
	}
	if !d.hasCredentialResolver() {
		return gatewayRoute{}, cloudNotAllowedError()
	}
	return gatewayRoute{cloudTarget: target, provider: provider, requestModel: requestModel, traits: traits}, nil
}

func (d *gatewayDispatcher) resolveLocal(publicID, requestModel string, traits gatewayRequestTraits) (gatewayRoute, *gatewayDispatchError) {
	if strings.HasPrefix(publicID, "local/") {
		engine, model, ok := parseLocalPublicModelID(publicID)
		if !ok {
			return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "model is unavailable", kind: "model_not_found"}
		}
		f := d.proxy.facadeFor(engine)
		if f == nil || !facadeAdvertisesModel(f, model) {
			return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "model is unavailable", kind: "model_not_found"}
		}
		if missing := missingGatewayCapabilities(localGatewayCapabilities(f.profile.Name), traits.capabilities); len(missing) > 0 {
			return gatewayRoute{}, unsupportedGatewayCapabilities(missing)
		}
		return gatewayRoute{facade: f, requestModel: requestModel, upstreamModel: model, traits: traits}, nil
	}
	var candidates []*facade
	for _, f := range d.proxy.enabledFacades() {
		for _, node := range f.discovery.Nodes() {
			if nodeAdvertisesModel(f.profile, node, publicID) {
				candidates = append(candidates, f)
				break
			}
		}
	}
	if len(candidates) == 0 {
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusNotFound, message: "model is unavailable", kind: "model_not_found"}
	}
	if len(candidates) > 1 {
		return gatewayRoute{}, &gatewayDispatchError{status: http.StatusConflict, message: "model ID is ambiguous; use a fully qualified local model ID", kind: "model_conflict"}
	}
	f := candidates[0]
	if missing := missingGatewayCapabilities(localGatewayCapabilities(f.profile.Name), traits.capabilities); len(missing) > 0 {
		return gatewayRoute{}, unsupportedGatewayCapabilities(missing)
	}
	return gatewayRoute{facade: f, requestModel: requestModel, upstreamModel: publicID, traits: traits}, nil
}

func facadeAdvertisesModel(f *facade, model string) bool {
	for _, node := range f.discovery.Nodes() {
		if nodeAdvertisesModel(f.profile, node, model) {
			return true
		}
	}
	return false
}

func localGatewayCapabilities(engine string) map[string]bool {
	return modelCapabilities(engine)
} //本地网关支持chat和streaming两种能力

func localPublicModelID(engine, model string) string {
	return "local/" + engine + "/" + url.PathEscape(model)
}

// 该函数是本地模型 ID 格式校验 + 解析工具，严格校验 local/目录/模型名 格式，返回目录、解码后的模型名和成功标识。
func parseLocalPublicModelID(publicID string) (string, string, bool) {
	parts := strings.SplitN(publicID, "/", 3)
	if len(parts) != 3 || parts[0] != "local" || parts[1] == "" || parts[2] == "" {
		return "", "", false
	}
	model, err := url.PathUnescape(parts[2])
	if err != nil || strings.TrimSpace(model) == "" {
		return "", "", false
	}
	return parts[1], model, true
}

func (d *gatewayDispatcher) dispatchCloud(w http.ResponseWriter, r *http.Request, body []byte, route gatewayRoute) {
	d.dispatchCloudForCaller(w, r, body, route, "")
}

func (d *gatewayDispatcher) dispatchCloudForCaller(w http.ResponseWriter, r *http.Request, body []byte, route gatewayRoute, callerNode string) {
	d.credentialMu.RLock()
	resolver := d.resolveCredential
	d.credentialMu.RUnlock()
	if resolver == nil {
		if d.dispatchLocalFallback(w, r, body, route, "cloud_not_allowed") {
			return
		}
		writeGatewayError(w, http.StatusForbidden, "cloud access is not authorized", "cloud_not_allowed")
		return
	}
	credential, err := resolver(route.provider.AuthRef)
	if err != nil || credential == "" {
		if d.dispatchLocalFallback(w, r, body, route, "cloud_not_allowed") {
			return
		}
		writeGatewayError(w, http.StatusForbidden, "cloud access is not authorized", "cloud_not_allowed")
		return
	}
	settings := d.settings.Load()
	if !validPositiveMoney(settings.monthlyBudgetUSD) || !validPositiveMoney(settings.perRequestMaxEstimatedCostUSD) {
		if d.dispatchLocalFallback(w, r, body, route, "cloud_not_allowed") {
			return
		}
		writeGatewayError(w, http.StatusForbidden, "cloud use requires an explicit monthly budget and per-request limit", "cloud_not_allowed")
		return
	}
	budget, budgetErr := d.cloudBudgetLedger()
	if budgetErr != nil || budget == nil {
		if d.dispatchLocalFallback(w, r, body, route, "quota_unavailable") {
			return
		}
		writeGatewayError(w, http.StatusServiceUnavailable, "cloud budget ledger is unavailable", "quota_unavailable")
		return
	}
	reservation, err := budget.reserve(settings.monthlyBudgetUSD, settings.perRequestMaxEstimatedCostUSD)
	if err != nil {
		if d.dispatchLocalFallback(w, r, body, route, cloudBudgetErrorKind(err)) {
			return
		}
		if errors.Is(err, errCloudQuotaExceeded) {
			writeGatewayError(w, http.StatusTooManyRequests, "cloud monthly budget is exhausted", "quota_exceeded")
		} else if errors.Is(err, errCloudBudgetUnavailable) {
			writeGatewayError(w, http.StatusForbidden, "cloud use requires an explicit monthly budget and per-request limit", "cloud_not_allowed")
		} else {
			writeGatewayError(w, http.StatusServiceUnavailable, "cloud budget ledger is unavailable", "quota_unavailable")
		}
		return
	}
	workload := newGatewayCloudWorkload(d.proxy, route, callerNode)
	response, err := d.client.DoChat(r.Context(), route.provider, route.cloudTarget, route.requestModel, credential, body)
	if err != nil {
		settleCloudBudget(reservation, route)
		if r.Context().Err() != nil {
			workload.finish("cancelled", "cloud request was cancelled", nil, nil)
		} else {
			workload.finish("failed", "cloud provider request failed", nil, nil)
		}
		status, message, kind := mapCloudGatewayError(err)
		writeGatewayError(w, status, message, kind)
		return
	}
	defer response.Body.Close()
	workload.started()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		usage := reportedCloudUsage(response.Body)
		settleCloudBudget(reservation, route)
		if response.StatusCode == http.StatusTooManyRequests {
			if retryAfter := safeRetryAfter(response.Header.Get("Retry-After")); retryAfter != "" {
				w.Header().Set("Retry-After", retryAfter)
			}
		}
		writeProviderHTTPError(w, response.StatusCode)
		workload.finish("failed", "cloud provider request failed", usage, nil)
		return
	}
	contentType := response.Header.Get("Content-Type")
	isCloudSSE := route.traits.stream && cloudResponseIsSSE(contentType)
	if isCloudSSE {
		if _, ok := w.(http.Flusher); !ok {
			settleCloudBudget(reservation, route)
			writeGatewayError(w, http.StatusBadGateway, "streaming responses are not supported", "provider_error")
			workload.finish("failed", "streaming response is not supported", reportedCloudUsage(response.Body), nil)
			return
		}
	}
	for _, header := range []string{"Content-Type", "Cache-Control", "Retry-After"} {
		if value := safeCloudResponseHeader(header, response.Header.Get(header)); value != "" {
			w.Header().Set(header, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	var copyErr error
	if isCloudSSE {
		copyErr = copyCloudStream(w, response.Body)
	} else {
		_, copyErr = io.Copy(w, response.Body)
	}
	usage := reportedCloudUsage(response.Body)
	settleCloudBudget(reservation, route)
	if copyErr != nil {
		if r.Context().Err() != nil {
			workload.finish("cancelled", "cloud request was cancelled", usage, nil)
		} else {
			workload.finish("failed", "cloud response ended before completion", usage, nil)
			slogCloudStreamError(route, response.StatusCode, copyErr)
		}
		return
	}
	workload.complete(usage, nil)
}

func cloudBudgetErrorKind(err error) string {
	switch {
	case errors.Is(err, errCloudQuotaExceeded):
		return "budget_exceeded"
	case errors.Is(err, errCloudBudgetUnavailable):
		return "cloud_not_allowed"
	default:
		return "quota_unavailable"
	}
}

func (d *gatewayDispatcher) dispatchLocalFallback(
	w http.ResponseWriter,
	r *http.Request,
	body []byte,
	route gatewayRoute,
	reason string,
) bool {
	if route.localFallback == nil || route.localFallback.facade == nil {
		return false
	}
	localRoute := route.localFallback
	forwardBody, err := rewriteGatewayModel(body, localRoute.upstreamModel)
	if err != nil {
		writeGatewayError(w, http.StatusBadRequest, "chat request is invalid", "invalid_request_error")
		return true
	}
	slog.Debug("Gateway used local execution because Cloud fallback was unavailable", "reason", reason,
		"model", route.requestModel, "engine", localRoute.facade.profile.Name)
	r.Body = io.NopCloser(bytes.NewReader(forwardBody))
	r.ContentLength = int64(len(forwardBody))
	localRoute.facade.handleHTTP(w, r)
	return true
}

func copyCloudStream(w http.ResponseWriter, body io.Reader) error {
	flusher, ok := w.(http.Flusher)
	if !ok {
		return errors.New("streaming response writer does not support flushing")
	}
	buffer := make([]byte, 32<<10)
	for {
		n, readErr := body.Read(buffer)
		if n > 0 {
			if _, writeErr := w.Write(buffer[:n]); writeErr != nil {
				return writeErr
			}
			flusher.Flush()
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				return nil
			}
			return readErr
		}
	}
}

func reportedCloudUsage(body io.ReadCloser) *CloudUsage {
	reporter, ok := body.(cloudUsageReporter)
	if !ok {
		return nil
	}
	usage := reporter.cloudUsage()
	if usage == nil {
		return nil
	}
	copy := *usage
	return &copy
}

func settleCloudBudget(reservation *cloudBudgetReservation, route gatewayRoute) {
	// The first version reserves and charges its configured request ceiling.
	// Usage is still recorded; precise settlement waits until a price table is
	// configured instead of pretending an unknown bill is zero.
	if err := reservation.settle(nil); err != nil {
		slog.Warn("failed to persist cloud budget settlement", "provider_id", route.cloudTarget.ProviderID,
			"model", route.requestModel)
	}
}

func writeProviderHTTPError(w http.ResponseWriter, status int) {
	switch {
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		writeGatewayError(w, http.StatusBadGateway, "cloud provider authorization failed", "provider_auth_failed")
	case status == http.StatusTooManyRequests:
		writeGatewayError(w, http.StatusTooManyRequests, "cloud provider rate limit was reached", "provider_rate_limited")
	case status >= http.StatusInternalServerError:
		writeGatewayError(w, http.StatusBadGateway, "cloud provider request failed", "provider_error")
	default:
		writeGatewayError(w, http.StatusBadRequest, "cloud provider rejected the request", "provider_error")
	}
}

func safeRetryAfter(value string) string {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 128 || strings.ContainsAny(value, "\r\n") {
		return ""
	}
	if seconds, err := strconv.ParseUint(value, 10, 64); err == nil && seconds <= uint64((1<<63-1)/int64(time.Second)) {
		return strconv.FormatUint(seconds, 10)
	}
	if _, err := http.ParseTime(value); err == nil {
		return value
	}
	return ""
}

func safeCloudResponseHeader(name, value string) string {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 4096 || strings.ContainsAny(value, "\r\n") {
		return ""
	}
	for _, character := range value {
		if character < 0x20 && character != '\t' || character == 0x7f {
			return ""
		}
	}
	switch name {
	case "Content-Type":
		if _, _, err := mime.ParseMediaType(value); err != nil {
			return ""
		}
		return value
	case "Cache-Control":
		return value
	case "Retry-After":
		return safeRetryAfter(value)
	default:
		return ""
	}
}

func cloudResponseIsSSE(value string) bool {
	mediaType, _, err := mime.ParseMediaType(value)
	return err == nil && strings.EqualFold(mediaType, "text/event-stream")
}

func slogCloudStreamError(route gatewayRoute, status int, err error) {
	code, _ := cloudErrorCodeOf(err)
	if code == "" {
		code = cloudErrProviderError
	}
	// Request content and the underlying transport error are intentionally omitted.
	slog.Warn("cloud response ended before completion", "provider_id", route.cloudTarget.ProviderID,
		"model", route.requestModel, "status", status, "error_code", string(code))
}

func cloudNotAllowedError() *gatewayDispatchError {
	return &gatewayDispatchError{status: http.StatusForbidden, message: "cloud access is not authorized", kind: "cloud_not_allowed"}
}

func unsupportedGatewayCapabilities(missing []string) *gatewayDispatchError {
	return &gatewayDispatchError{
		status:  http.StatusUnprocessableEntity,
		message: "selected model does not support required capabilities: " + strings.Join(missing, ", "),
		kind:    "unsupported_capability",
	}
}

func missingGatewayCapabilities(available, required map[string]bool) []string {
	var missing []string
	for capability, needed := range required {
		if needed && !available[capability] {
			missing = append(missing, capability)
		}
	}
	sort.Strings(missing)
	return missing
}

func (d *gatewayDispatcher) hasCredentialResolver() bool {
	d.credentialMu.RLock()
	defer d.credentialMu.RUnlock()
	return d.resolveCredential != nil
}

func (d *gatewayDispatcher) closeIdleConnections() {
	d.client.CloseIdleConnections()
}

func mapCloudGatewayError(err error) (int, string, string) {
	code, ok := cloudErrorCodeOf(err)
	if !ok {
		return http.StatusBadGateway, "cloud provider request failed", "provider_error"
	}
	switch code {
	case cloudErrUnavailable, cloudErrInvalidCredential, cloudErrProviderCanceled:
		return http.StatusForbidden, "cloud access is not authorized", "cloud_not_allowed"
	case cloudErrInvalidRequest:
		return http.StatusBadRequest, "chat request is invalid", "invalid_request_error"
	case cloudErrInvalidEndpoint:
		return http.StatusBadGateway, "configured provider endpoint is invalid", "provider_error"
	case cloudErrProviderTimeout:
		return http.StatusGatewayTimeout, "cloud provider request timed out", "provider_timeout"
	case cloudErrResponseTooLarge, cloudErrResponseRead, cloudErrResponseClose, cloudErrStreamTruncated, cloudErrSSEEventTooLarge:
		return http.StatusBadGateway, "cloud provider returned an invalid response", "provider_error"
	default:
		return http.StatusBadGateway, "cloud provider request failed", "provider_error"
	}
}

// ：解析网关请求 JSON 数据，提取核心特征并生成gatewayRequestTraits结构体，返回解析结果和错误。
func parseGatewayRequestTraits(body []byte) (gatewayRequestTraits, error) {
	var request struct {
		Model          string          `json:"model"`
		Stream         bool            `json:"stream"`
		Tools          json.RawMessage `json:"tools"`
		ToolChoice     json.RawMessage `json:"tool_choice"`
		ResponseFormat struct {
			Type string `json:"type"`
		} `json:"response_format"`
		StreamOptions json.RawMessage `json:"stream_options"`
		Messages      []struct {
			Role      string          `json:"role"`
			ToolCalls json.RawMessage `json:"tool_calls"`
			Content   json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(body, &request); err != nil {
		return gatewayRequestTraits{}, err
	}
	traits := gatewayRequestTraits{model: request.Model, stream: request.Stream, capabilities: map[string]bool{"chat": true}}
	if request.Stream || len(request.StreamOptions) > 0 && !bytes.Equal(request.StreamOptions, []byte("null")) {
		traits.capabilities["streaming"] = true
	}
	if nonEmptyJSONArray(request.Tools) || requiresToolChoice(request.ToolChoice) {
		traits.capabilities["tools"] = true
	}
	for _, message := range request.Messages {
		if message.Role == "tool" || nonEmptyJSONArray(message.ToolCalls) {
			traits.capabilities["tools"] = true
		}
		var parts []struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(message.Content, &parts) == nil {
			for _, part := range parts {
				if part.Type == "image_url" || part.Type == "image" || part.Type == "input_image" {
					traits.capabilities["vision"] = true
				}
			}
		}
	}
	switch request.ResponseFormat.Type {
	case "":
	case "json_object":
		traits.capabilities["json_object"] = true
	case "json_schema":
		traits.capabilities["json_schema"] = true
	case "text":
	default:
		return gatewayRequestTraits{}, fmt.Errorf("unsupported response_format type")
	}
	return traits, nil
}

func nonEmptyJSONArray(raw json.RawMessage) bool {
	var values []json.RawMessage
	return json.Unmarshal(raw, &values) == nil && len(values) > 0
}

func requiresToolChoice(raw json.RawMessage) bool {
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) || bytes.Equal(raw, []byte(`"none"`)) {
		return false
	}
	return true
}

func (d *gatewayDispatcher) setCredentialResolver(resolver func(string) (string, error)) {
	d.credentialMu.Lock()
	d.resolveCredential = resolver
	d.credentialMu.Unlock()
}

func (d *gatewayDispatcher) setProviderCredential(providerID, credential string) {
	d.credentialMu.Lock()
	if d.providerCredentials == nil {
		d.providerCredentials = make(map[string]string)
	}
	if credential == "" {
		delete(d.providerCredentials, providerID)
	} else {
		d.providerCredentials[providerID] = credential
	}
	d.resolveCredential = func(authRef string) (string, error) {
		d.credentialMu.RLock()
		defer d.credentialMu.RUnlock()
		credential := d.providerCredentials[authRef]
		if credential == "" {
			return "", errors.New("provider credential is not configured")
		}
		return credential, nil
	}
	d.credentialMu.Unlock()
}

func (d *gatewayDispatcher) pruneProviderCredentials(snapshot *cloudRegistrySnapshot) {
	d.credentialMu.Lock()
	defer d.credentialMu.Unlock()
	for authRef := range d.providerCredentials {
		if !snapshot.providerByAuthRef(authRef) {
			delete(d.providerCredentials, authRef)
		}
	}
}
