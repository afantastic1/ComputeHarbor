// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useCallback, useEffect, useState } from 'react'
import {
    Anchor,
    Button,
    Flex,
    FormField,
    Stack,
    Switch,
    Text,
    TextInput
} from '@nvidia/foundations-react-core'
import type {
    StrataConnectionError,
    StrataProbeResult,
    StrataSettings
} from '@/shared/types/strata'
import { InlineErrorBanner } from '@/ui/components/InlineErrorBanner'
import getErrorString from '@/shared/utils/get-error-string'

const DEFAULT_SETTINGS: StrataSettings = {
    enabled: false,
    endpoint: 'http://127.0.0.1:8080',
    modelId: '',
    apiKeyEnv: ''
}

const ERROR_MESSAGES: Record<StrataConnectionError, string> = {
    invalid_endpoint: 'Use an HTTP loopback address such as http://127.0.0.1:8080/v1.',
    not_running: 'Strata is not running at this endpoint.',
    authentication_failed: 'Strata rejected the configured API key.',
    model_not_recognized: 'The configured model ID is not listed by Strata.',
    protocol_incompatible: 'Strata did not return a compatible OpenAI model list.'
}

function probeMessage(probe: StrataProbeResult): string {
    if (probe.connected) return `Connected · ${probe.modelIds.length} model(s) found.`
    return probe.errorCode ? ERROR_MESSAGES[probe.errorCode] : 'Strata is unavailable.'
}

export default function StrataSettingsCard() {
    const [settings, setSettings] = useState(DEFAULT_SETTINGS)
    const [probe, setProbe] = useState<StrataProbeResult | null>(null)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        let active = true
        window.pairApi.strata
            .getSettings()
            .then(result => {
                if (!active) return
                setSettings(result.settings)
                setProbe(result.probe)
            })
            .catch(() => {
                if (active) setError('Strata settings could not be loaded.')
            })
        return () => {
            active = false
        }
    }, [])

    const update = useCallback(
        <K extends keyof StrataSettings>(key: K, value: StrataSettings[K]) => {
            setSettings(current => ({ ...current, [key]: value }))
            setProbe(null)
            setError(null)
        },
        []
    )

    const testConnection = useCallback(async () => {
        setBusy(true)
        setError(null)
        try {
            setProbe(await window.pairApi.strata.testConnection(settings))
        } catch (cause) {
            setError(`Strata connection test failed: ${getErrorString(cause)}`)
        } finally {
            setBusy(false)
        }
    }, [settings])

    const saveSettings = useCallback(async () => {
        setBusy(true)
        setError(null)
        try {
            const result = await window.pairApi.strata.saveSettings(settings)
            setSettings(result.settings)
            setProbe(result.probe)
        } catch (cause) {
            setError(`Strata settings could not be saved: ${getErrorString(cause)}`)
        } finally {
            setBusy(false)
        }
    }, [settings])

    return (
        <Stack gap="4" className="rounded border border-base p-4">
            <Flex align="center" justify="between" gap="3">
                <Stack gap="1">
                    <Text kind="body/bold/md">Strata (External)</Text>
                    <Text kind="body/regular/sm">
                        Connect to a Strata instance already running on this PC.
                    </Text>
                </Stack>
                <Switch
                    size="small"
                    checked={settings.enabled}
                    onCheckedChange={value => update('enabled', value)}
                    aria-label="Enable Strata connection"
                />
            </Flex>
            <FormField slotLabel="Loopback endpoint (root or /v1)">
                <TextInput
                    size="small"
                    value={settings.endpoint}
                    onValueChange={value => update('endpoint', value)}
                    placeholder="http://127.0.0.1:8080"
                />
            </FormField>
            <FormField slotLabel="Expected model ID (optional)">
                <TextInput
                    size="small"
                    value={settings.modelId}
                    onValueChange={value => update('modelId', value)}
                    placeholder="Validate an ID returned by GET /v1/models"
                />
            </FormField>
            <FormField slotLabel="API key environment variable name (optional; do not enter the key)">
                <TextInput
                    size="small"
                    value={settings.apiKeyEnv}
                    onValueChange={value => update('apiKeyEnv', value)}
                    placeholder="STRATA_API_KEY"
                />
            </FormField>
            {probe && <Text kind="body/regular/sm">{probeMessage(probe)}</Text>}
            {error && <InlineErrorBanner severity="error" message={error} />}
            <Flex align="center" gap="3" wrap="wrap">
                <Button kind="secondary" size="small" onClick={testConnection} disabled={busy}>
                    Test connection
                </Button>
                <Button kind="primary" size="small" onClick={saveSettings} disabled={busy}>
                    Save Strata settings
                </Button>
                <Anchor href="https://github.com/Niko1221/Strata" target="_blank" rel="noreferrer">
                    Strata documentation
                </Anchor>
            </Flex>
        </Stack>
    )
}
