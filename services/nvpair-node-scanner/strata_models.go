// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"

	"nvpair-shared/noderec"
)

func (d *daemon) enrichStrataModelsCandidates(node *noderec.DirectoryNode, hosts []string) {
	service, present := node.Services[noderec.ServiceStrata]
	if !present || service.Port <= 0 || len(hosts) == 0 {
		return
	}
	key := hostKey{hostUUID: node.HostUUID, service: noderec.ServiceStrata}
	_, models, ok := askRemembered(&d.enrichHosts, key, hosts, func(host string) ([]string, bool) {
		return d.fetchStrataModels(host, service.Port, node.ClusterUUID)
	})
	if !ok {
		d.infoMu.Lock()
		models = d.lastModelsByEngine[node.HostUUID]["strata"]
		d.infoMu.Unlock()
		if len(models) == 0 {
			return
		}
	}

	d.infoMu.Lock()
	if d.lastModelsByEngine == nil {
		d.lastModelsByEngine = make(map[string]map[string][]string)
	}
	byEngine := cloneModelInventory(d.lastModelsByEngine[node.HostUUID])
	byEngine["strata"] = append([]string(nil), models...)
	d.lastModelsByEngine[node.HostUUID] = byEngine
	d.lastModels[node.HostUUID] = mergeModelNames(d.lastModels[node.HostUUID], models)
	d.infoMu.Unlock()

	node.ModelsByEngine = cloneModelInventory(node.ModelsByEngine)
	node.ModelsByEngine["strata"] = append([]string(nil), models...)
	node.Models = mergeModelNames(node.Models, models)
}

func (d *daemon) fetchStrataModels(host string, port int, clusterUUID string) ([]string, bool) {
	client, scheme, ok := d.modelsClient(host, clusterUUID)
	if !ok {
		return nil, false
	}
	url := fmt.Sprintf("%s://%s/v1/models", scheme, net.JoinHostPort(host, strconv.Itoa(port)))
	resp, err := client.Get(url)
	if err != nil {
		return nil, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, false
	}
	var body struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, false
	}
	models := make([]string, 0, len(body.Data))
	for _, model := range body.Data {
		if model.ID != "" {
			models = append(models, model.ID)
		}
	}
	return models, true
}

func cloneModelInventory(source map[string][]string) map[string][]string {
	result := make(map[string][]string, len(source))
	for engine, models := range source {
		result[engine] = append([]string(nil), models...)
	}
	return result
}

func mergeModelNames(existing, added []string) []string {
	seen := make(map[string]bool, len(existing)+len(added))
	result := make([]string, 0, len(existing)+len(added))
	for _, model := range append(append([]string(nil), existing...), added...) {
		if model != "" && !seen[model] {
			seen[model] = true
			result = append(result, model)
		}
	}
	return result
}
