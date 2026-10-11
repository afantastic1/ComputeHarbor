// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import "fmt"

func rejectsUnverifiedCapabilities(profile engineProfile, body []byte) (bool, error) {
	if !profile.External {
		return false, nil
	}
	traits, err := parseGatewayRequestTraits(body)
	if err != nil {
		return false, fmt.Errorf("parse request capabilities: %w", err)
	}
	return traits.capabilities["vision"] || traits.capabilities["json_object"] || traits.capabilities["json_schema"], nil
}
