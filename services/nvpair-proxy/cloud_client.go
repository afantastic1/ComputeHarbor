// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	defaultCloudMaxConcurrent         = 8
	defaultCloudMaxResponseBytes      = 8 << 20
	defaultCloudMaxSSEEventBytes      = 1 << 20
	cloudDialTimeout                  = 10 * time.Second
	cloudTLSHandshakeTimeout          = 10 * time.Second
	defaultCloudResponseHeaderTimeout = 2 * time.Minute
	cloudIdleConnectionTimeout        = 90 * time.Second
	defaultCloudStreamIdleTimeout     = 2 * time.Minute
	defaultCloudNonStreamTimeout      = 2 * time.Minute
)

type cloudIPLookup func(context.Context, string, string) ([]net.IPAddr, error)

type cloudHTTPOptions struct {
	maxConcurrent         int
	maxResponseBytes      int64
	maxSSEEventBytes      int
	streamIdleTimeout     time.Duration
	nonStreamTimeout      time.Duration
	responseHeaderTimeout time.Duration
	allowLoopback         bool
	lookupIP              cloudIPLookup
	tlsConfig             *tls.Config
}

type cloudHTTPClient struct {
	client            *http.Client
	slots             chan struct{}
	maxResponseBytes  int64
	maxSSEEventBytes  int
	streamIdleTimeout time.Duration
	nonStreamTimeout  time.Duration
	allowLoopback     bool
	lookupIP          cloudIPLookup
}

func newCloudHTTPClient(options cloudHTTPOptions) *cloudHTTPClient {
	if options.maxConcurrent <= 0 {
		options.maxConcurrent = defaultCloudMaxConcurrent
	}
	if options.maxResponseBytes <= 0 {
		options.maxResponseBytes = defaultCloudMaxResponseBytes
	}
	if options.maxSSEEventBytes <= 0 {
		options.maxSSEEventBytes = defaultCloudMaxSSEEventBytes
	}
	if options.streamIdleTimeout <= 0 {
		options.streamIdleTimeout = defaultCloudStreamIdleTimeout
	}
	if options.nonStreamTimeout <= 0 {
		options.nonStreamTimeout = defaultCloudNonStreamTimeout
	}
	if options.responseHeaderTimeout <= 0 {
		options.responseHeaderTimeout = defaultCloudResponseHeaderTimeout
	}
	if options.lookupIP == nil {
		options.lookupIP = func(ctx context.Context, _, host string) ([]net.IPAddr, error) {
			return net.DefaultResolver.LookupIPAddr(ctx, host)
		}
	}
	transport := &http.Transport{
		Proxy:                 nil,
		DialContext:           nil,
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   cloudTLSHandshakeTimeout,
		ResponseHeaderTimeout: options.responseHeaderTimeout,
		IdleConnTimeout:       cloudIdleConnectionTimeout,
		MaxIdleConns:          options.maxConcurrent,
		MaxIdleConnsPerHost:   options.maxConcurrent,
		TLSClientConfig:       options.tlsConfig,
	}
	cloudClient := &cloudHTTPClient{
		slots:             make(chan struct{}, options.maxConcurrent),
		maxResponseBytes:  options.maxResponseBytes,
		maxSSEEventBytes:  options.maxSSEEventBytes,
		streamIdleTimeout: options.streamIdleTimeout,
		nonStreamTimeout:  options.nonStreamTimeout,
		allowLoopback:     options.allowLoopback,
		lookupIP:          options.lookupIP,
	}
	transport.DialContext = cloudClient.dialContext
	cloudClient.client = &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return cloudClient
}

func (c *cloudHTTPClient) CloseIdleConnections() {
	c.client.CloseIdleConnections()
}

func (c *cloudHTTPClient) DoChat(
	ctx context.Context,
	provider cloudProviderRuntime,
	target cloudModelTarget,
	responseModel string,
	credential string,
	rawBody []byte,
) (*http.Response, error) {
	if !provider.Enabled || !target.Enabled || provider.ID == "" || target.ProviderID != provider.ID {
		return nil, newCloudAdapterError(cloudErrUnavailable, nil)
	}
	if strings.TrimSpace(credential) == "" || len(credential) > 16<<10 || strings.ContainsAny(credential, "\r\n") {
		return nil, newCloudAdapterError(cloudErrInvalidCredential, nil)
	}
	requestBody, err := rewriteCloudRequestModel(rawBody, target.UpstreamID)
	if err != nil {
		return nil, newCloudAdapterError(cloudErrInvalidRequest, err)
	}
	endpoint, err := cloudChatEndpoint(provider.BaseURL)
	if err != nil {
		return nil, newCloudAdapterError(cloudErrInvalidEndpoint, err)
	}
	stream := cloudRequestWantsStream(requestBody)
	select {
	case c.slots <- struct{}{}:
	case <-ctx.Done():
		return nil, cloudTransportError(ctx.Err())
	}
	release := sync.OnceFunc(func() { <-c.slots })
	var requestContext context.Context
	var cancel context.CancelFunc
	if stream {
		requestContext, cancel = context.WithCancel(ctx)
	} else {
		requestContext, cancel = context.WithTimeout(ctx, c.nonStreamTimeout)
	}
	request, err := http.NewRequestWithContext(requestContext, http.MethodPost, endpoint, bytes.NewReader(requestBody))
	if err != nil {
		cancel()
		release()
		return nil, newCloudAdapterError(cloudErrInvalidEndpoint, err)
	}
	// POST replay is disabled even if the request constructor supplies GetBody.
	request.GetBody = nil
	request.Header.Set("Authorization", "Bearer "+credential)
	request.Header.Set("Content-Type", "application/json")
	if stream {
		request.Header.Set("Accept", "text/event-stream, application/json")
	} else {
		request.Header.Set("Accept", "application/json")
	}
	response, err := c.client.Do(request)
	if err != nil {
		cancel()
		release()
		return nil, cloudTransportError(err)
	}
	if stream && response.StatusCode >= http.StatusOK && response.StatusCode < http.StatusMultipleChoices &&
		strings.Contains(strings.ToLower(response.Header.Get("Content-Type")), "text/event-stream") {
		response.Body = &cloudSSEBody{
			ReadCloser: response.Body, maxEventBytes: c.maxSSEEventBytes,
			idleTimeout: c.streamIdleTimeout, responseModel: responseModel,
			cancel: cancel, release: release,
		}
		return response, nil
	}
	responseData, readErr := io.ReadAll(io.LimitReader(response.Body, c.maxResponseBytes+1))
	closeErr := response.Body.Close()
	cancel()
	release()
	if readErr != nil {
		if errors.Is(readErr, context.DeadlineExceeded) || errors.Is(readErr, context.Canceled) {
			return nil, cloudTransportError(readErr)
		}
		return nil, newCloudAdapterError(cloudErrResponseRead, readErr)
	}
	if closeErr != nil {
		return nil, newCloudAdapterError(cloudErrResponseClose, closeErr)
	}
	if int64(len(responseData)) > c.maxResponseBytes {
		return nil, newCloudAdapterError(cloudErrResponseTooLarge, nil)
	}
	if response.StatusCode >= http.StatusOK && response.StatusCode < http.StatusMultipleChoices {
		responseData = rewriteCloudResponseModel(responseData, responseModel)
	}
	response.Body = &cloudObservedBody{
		ReadCloser: io.NopCloser(bytes.NewReader(responseData)), usage: providerCloudUsage(responseData),
	}
	response.ContentLength = int64(len(responseData))
	return response, nil
}

func (c *cloudHTTPClient) TestProvider(ctx context.Context, provider cloudProviderRuntime, credential string) error {
	if provider.ID == "" || strings.TrimSpace(credential) == "" || len(credential) > 16<<10 || strings.ContainsAny(credential, "\r\n") {
		return newCloudAdapterError(cloudErrInvalidCredential, nil)
	}
	endpoint, err := cloudModelsEndpoint(provider.BaseURL)
	if err != nil {
		return newCloudAdapterError(cloudErrInvalidEndpoint, err)
	}
	select {
	case c.slots <- struct{}{}:
		defer func() { <-c.slots }()
	case <-ctx.Done():
		return cloudTransportError(ctx.Err())
	}
	requestContext, cancel := context.WithTimeout(ctx, c.nonStreamTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(requestContext, http.MethodGet, endpoint, nil)
	if err != nil {
		return newCloudAdapterError(cloudErrInvalidEndpoint, err)
	}
	request.Header.Set("Authorization", "Bearer "+credential)
	request.Header.Set("Accept", "application/json")
	response, err := c.client.Do(request)
	if err != nil {
		return cloudTransportError(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, c.maxResponseBytes+1))
	if err != nil {
		return newCloudAdapterError(cloudErrResponseRead, err)
	}
	if int64(len(body)) > c.maxResponseBytes {
		return newCloudAdapterError(cloudErrResponseTooLarge, nil)
	}
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return newCloudAdapterError(cloudErrProviderRejected, nil)
	}
	return nil
}

func rewriteCloudResponseModel(responseBody []byte, publicModelID string) []byte {
	if publicModelID == "" {
		return responseBody
	}
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(responseBody, &payload); err != nil || payload == nil {
		return responseBody
	}
	if _, exists := payload["model"]; !exists {
		return responseBody
	}
	encoded, err := json.Marshal(publicModelID)
	if err != nil {
		return responseBody
	}
	payload["model"] = encoded
	rewritten, err := json.Marshal(payload)
	if err != nil {
		return responseBody
	}
	return rewritten
}

func rewriteCloudRequestModel(rawBody []byte, upstreamID string) ([]byte, error) {
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(rawBody, &payload); err != nil || payload == nil {
		return nil, newCloudAdapterError(cloudErrInvalidRequest, err)
	}
	encoded, err := json.Marshal(upstreamID)
	if err != nil {
		return nil, newCloudAdapterError(cloudErrInvalidRequest, err)
	}
	payload["model"] = encoded
	return json.Marshal(payload)
}

func cloudChatEndpoint(baseURL string) (string, error) {
	parsed, err := url.Parse(baseURL)
	if err != nil || parsed.Scheme != "https" && parsed.Scheme != "http" || parsed.Hostname() == "" {
		return "", errors.New("provider API base URL is invalid")
	}
	path := strings.TrimRight(parsed.Path, "/")
	if strings.HasSuffix(path, "/v1") {
		parsed.Path = path + "/chat/completions"
	} else {
		parsed.Path = path + "/v1/chat/completions"
	}
	parsed.RawPath = ""
	return parsed.String(), nil
}

func cloudModelsEndpoint(baseURL string) (string, error) {
	parsed, err := url.Parse(baseURL)
	if err != nil || parsed.Scheme != "https" && parsed.Scheme != "http" || parsed.Hostname() == "" {
		return "", errors.New("provider API base URL is invalid")
	}
	path := strings.TrimRight(parsed.Path, "/")
	if strings.HasSuffix(path, "/v1") {
		parsed.Path = path + "/models"
	} else {
		parsed.Path = path + "/v1/models"
	}
	parsed.RawPath = ""
	return parsed.String(), nil
}

func cloudRequestWantsStream(rawBody []byte) bool {
	var request struct {
		Stream bool `json:"stream"`
	}
	return json.Unmarshal(rawBody, &request) == nil && request.Stream
}

func (c *cloudHTTPClient) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, errors.New("provider address is invalid")
	}
	if ip := net.ParseIP(host); ip != nil {
		if isForbiddenCloudIP(ip) && !(c.allowLoopback && ip.IsLoopback()) {
			return nil, errors.New("provider resolved to a disallowed IP address")
		}
		return (&net.Dialer{Timeout: cloudDialTimeout}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
	}
	addresses, err := c.lookupIP(ctx, "ip", host)
	if err != nil {
		return nil, errors.New("provider hostname could not be resolved")
	}
	if len(addresses) == 0 {
		return nil, errors.New("provider hostname resolved to no addresses")
	}
	for _, resolved := range addresses {
		ip := resolved.IP
		if isForbiddenCloudIP(ip) && !(c.allowLoopback && ip.IsLoopback()) {
			return nil, errors.New("provider resolved to a disallowed IP address")
		}
		connection, dialErr := (&net.Dialer{Timeout: cloudDialTimeout}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if dialErr == nil {
			return connection, nil
		}
	}
	return nil, errors.New("provider connection failed")
}

func isForbiddenCloudIP(address net.IP) bool {
	if address == nil || !address.IsGlobalUnicast() || address.IsPrivate() || address.IsLoopback() ||
		address.IsLinkLocalUnicast() || address.IsLinkLocalMulticast() {
		return true
	}
	reservedRanges := []string{
		"100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
		"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "2001::/23",
		"2001:db8::/32", "2002::/16", "64:ff9b::/96",
	}
	if address.To4() == nil {
		_, globalIPv6, _ := net.ParseCIDR("2000::/3")
		if globalIPv6 == nil || !globalIPv6.Contains(address) {
			return true
		}
	}
	for _, value := range reservedRanges {
		_, block, err := net.ParseCIDR(value)
		if err == nil && block.Contains(address) {
			return true
		}
	}
	return false
}

type cloudSSEBody struct {
	io.ReadCloser
	maxEventBytes int
	idleTimeout   time.Duration
	responseModel string
	event         []byte
	line          []byte
	input         []byte
	inputOffset   int
	inputErr      error
	output        []byte
	outputOffset  int
	eventBytes    int
	sawDone       bool
	cancel        context.CancelFunc
	release       func()
	usage         *CloudUsage
	closeOnce     sync.Once
}

type cloudUsageReporter interface {
	cloudUsage() *CloudUsage
}

type cloudObservedBody struct {
	io.ReadCloser
	usage *CloudUsage
}

func (b *cloudObservedBody) cloudUsage() *CloudUsage { return b.usage }

func providerCloudUsage(body []byte) *CloudUsage {
	var payload struct {
		Usage *struct {
			PromptTokens     *int64 `json:"prompt_tokens"`
			CompletionTokens *int64 `json:"completion_tokens"`
			InputTokens      *int64 `json:"input_tokens"`
			OutputTokens     *int64 `json:"output_tokens"`
		} `json:"usage"`
	}
	if json.Unmarshal(body, &payload) != nil || payload.Usage == nil {
		return nil
	}
	input := payload.Usage.PromptTokens
	if input == nil {
		input = payload.Usage.InputTokens
	}
	output := payload.Usage.CompletionTokens
	if output == nil {
		output = payload.Usage.OutputTokens
	}
	if input == nil || output == nil || *input < 0 || *output < 0 {
		return nil
	}
	return &CloudUsage{InputTokens: *input, OutputTokens: *output}
}

func (b *cloudSSEBody) cloudUsage() *CloudUsage { return b.usage }

func (b *cloudSSEBody) Read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	for {
		if b.outputOffset < len(b.output) {
			n := copy(buffer, b.output[b.outputOffset:])
			b.outputOffset += n
			if b.outputOffset == len(b.output) {
				b.output = nil
				b.outputOffset = 0
			}
			return n, nil
		}
		if b.sawDone {
			return 0, io.EOF
		}
		if b.inputOffset < len(b.input) {
			value := b.input[b.inputOffset]
			b.inputOffset++
			b.event = append(b.event, value)
			b.eventBytes++
			if b.eventBytes > b.maxEventBytes {
				return 0, newCloudAdapterError(cloudErrSSEEventTooLarge, nil)
			}
			if value == '\n' {
				line := bytes.TrimSuffix(b.line, []byte{'\r'})
				if len(line) == 0 {
					if usage := providerCloudUsageFromSSE(b.event); usage != nil {
						b.usage = usage
					}
					var terminal bool
					b.output, terminal = rewriteCloudSSEEvent(b.event, b.responseModel)
					b.sawDone = terminal
					b.outputOffset = 0
					b.event = nil
					b.eventBytes = 0
				} else {
					b.line = b.line[:0]
				}
			} else if value != '\r' {
				b.line = append(b.line, value)
			}
			continue
		}
		if b.inputErr != nil {
			if errors.Is(b.inputErr, io.EOF) {
				if b.sawDone {
					return 0, io.EOF
				}
				return 0, newCloudAdapterError(cloudErrStreamTruncated, io.ErrUnexpectedEOF)
			}
			return 0, cloudTransportError(b.inputErr)
		}
		if err := b.readChunk(); err != nil {
			return 0, err
		}
	}
}

func providerCloudUsageFromSSE(event []byte) *CloudUsage {
	for start := 0; start < len(event); {
		lineEnd := bytes.IndexByte(event[start:], '\n')
		end := len(event)
		next := len(event)
		if lineEnd >= 0 {
			next = start + lineEnd + 1
			end = next - 1
		}
		if end > start && event[end-1] == '\r' {
			end--
		}
		line := event[start:end]
		if bytes.HasPrefix(line, []byte("data:")) {
			value := bytes.TrimSpace(line[len("data:"):])
			if usage := providerCloudUsage(value); usage != nil {
				return usage
			}
		}
		if next <= start {
			break
		}
		start = next
	}
	return nil
}

func (b *cloudSSEBody) readChunk() error {
	type readResult struct {
		data []byte
		n    int
		err  error
	}
	result := make(chan readResult, 1)
	readBuffer := make([]byte, 32<<10)
	go func() {
		n, err := b.ReadCloser.Read(readBuffer)
		result <- readResult{data: readBuffer, n: n, err: err}
	}()
	timer := time.NewTimer(b.idleTimeout)
	defer timer.Stop()
	select {
	case read := <-result:
		b.input = read.data[:read.n]
		b.inputOffset = 0
		b.inputErr = read.err
		if read.n == 0 && read.err == nil {
			return nil
		}
		return nil
	case <-timer.C:
		b.cancel()
		<-result
		return newCloudAdapterError(cloudErrProviderTimeout, context.DeadlineExceeded)
	}
}

func rewriteCloudSSEEvent(event []byte, publicModelID string) ([]byte, bool) {
	type dataField struct {
		start      int
		valueStart int
		valueEnd   int
		value      []byte
	}
	var fields []dataField
	for start := 0; start < len(event); {
		lineEnd := bytes.IndexByte(event[start:], '\n')
		contentEnd := len(event)
		next := len(event)
		if lineEnd >= 0 {
			next = start + lineEnd + 1
			contentEnd = next - 1
		}
		if contentEnd > start && event[contentEnd-1] == '\r' {
			contentEnd--
		}
		line := event[start:contentEnd]
		if bytes.HasPrefix(line, []byte("data:")) {
			valueStart := start + len("data:")
			if valueStart < contentEnd && event[valueStart] == ' ' {
				valueStart++
			}
			fields = append(fields, dataField{
				start: start, valueStart: valueStart, valueEnd: contentEnd,
				value: event[valueStart:contentEnd],
			})
		}
		if next <= start {
			break
		}
		start = next
	}
	var data []byte
	for index, field := range fields {
		if index > 0 {
			data = append(data, '\n')
		}
		data = append(data, field.value...)
	}
	if bytes.Equal(bytes.TrimSpace(data), []byte("[DONE]")) {
		return event, true
	}
	if publicModelID == "" || len(fields) != 1 {
		return event, false
	}
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(fields[0].value, &payload); err != nil || payload == nil {
		return event, false
	}
	if _, exists := payload["model"]; !exists {
		return event, false
	}
	encodedModel, err := json.Marshal(publicModelID)
	if err != nil {
		return event, false
	}
	payload["model"] = encodedModel
	encodedPayload, err := json.Marshal(payload)
	if err != nil {
		return event, false
	}
	rewritten := make([]byte, 0, len(event)-fields[0].valueEnd+fields[0].valueStart+len(encodedPayload))
	rewritten = append(rewritten, event[:fields[0].valueStart]...)
	rewritten = append(rewritten, encodedPayload...)
	rewritten = append(rewritten, event[fields[0].valueEnd:]...)
	return rewritten, false
}

func (b *cloudSSEBody) Close() error {
	var err error
	b.closeOnce.Do(func() {
		b.cancel()
		b.release()
		err = b.ReadCloser.Close()
	})
	return err
}
