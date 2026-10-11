# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDir
)

$ErrorActionPreference = 'Stop'
$buildOutputDir = Join-Path (Join-Path $PSScriptRoot '..') 'build/generated/pairBinaries/arm64-v8a'
$services = @(
    'nvpair-node-settings',
    'nvpair-ui-broker',
    'nvpair-engine-manager',
    'nvpair-node-scanner',
    'nvpair-node-info',
    'nvpair-cluster-manager',
    'nvpair-proxy',
    'nvpair-job-scheduler',
    'nvpair-workload-manager',
    'nvpair-errors'
)
& (Join-Path $PSScriptRoot 'build-pair-services.ps1') -OutputDir $buildOutputDir -Services $services

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$abiDir = Join-Path $OutputDir 'arm64-v8a'
New-Item -ItemType Directory -Force -Path $abiDir | Out-Null
foreach ($service in $services) {
    $libraryName = 'lib' + $service.Replace('-', '_') + '.so'
    $stagedPath = Join-Path $abiDir $libraryName
    Copy-Item (Join-Path $buildOutputDir $service) $stagedPath -Force

    & (Join-Path $PSScriptRoot 'verify-native-binaries.ps1') -BinaryPath $stagedPath
}
