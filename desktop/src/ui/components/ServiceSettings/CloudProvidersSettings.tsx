// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
    Button,
    Dropdown,
    Flex,
    FormField,
    Stack,
    Switch,
    Text,
    TextInput,
    type DropdownEntry
} from '@nvidia/foundations-react-core'
import type { CloudProviderConfig, CloudProvidersSettings } from '@/shared/types/cloud-providers'
import type { ClusterNode } from '@/shared/types/cluster'
import getErrorString from '@/shared/utils/get-error-string'
import { InlineErrorBanner } from '@/ui/components/InlineErrorBanner'
import { ExpandMore } from '@/ui/components/icons'

const EMPTY_SETTINGS: CloudProvidersSettings = {
    schema_version: 1,
    config: { schema_version: 1, providers: [] },
    cloudEnabled: false,
    policy: 'local_only',
    policyMigrationRequired: false,
    fallbackCloudModel: '',
    allowPaidFallback: false,
    monthlyBudgetUSD: 0,
    perRequestMaxEstimatedCostUSD: 0,
    authorizedNodes: []
}

const POLICY_LABELS: Record<CloudProvidersSettings['policy'], string> = {
    local_only: 'Local only',
    cloud_only: 'Cloud only',
    prefer_local: 'Prefer local'
}
const POLICY_VALUES: CloudProvidersSettings['policy'][] = [
    'local_only',
    'cloud_only',
    'prefer_local'
]

export default function CloudProvidersSettingsCard() {
    const [settings, setSettings] = useState(EMPTY_SETTINGS)
    const [editingProviderRefId, setEditingProviderRefId] = useState('')
    const [providerId, setProviderId] = useState('deepseek-primary')
    const [providerKind, setProviderKind] = useState<'deepseek' | 'generic'>('deepseek')
    const [providerEnabled, setProviderEnabled] = useState(true)
    const [baseUrl, setBaseUrl] = useState('https://api.deepseek.com')
    const [upstreamModel, setUpstreamModel] = useState('deepseek-chat')
    const [publicModelName, setPublicModelName] = useState('deepseek-chat')
    const [capabilitiesText, setCapabilitiesText] = useState('chat, streaming')
    const [credential, setCredential] = useState('')
    const [busy, setBusy] = useState(false)
    const [testing, setTesting] = useState(false)
    const [connectionResults, setConnectionResults] = useState<Record<string, boolean>>({})
    const [error, setError] = useState<string | null>(null)
    const [status, setStatus] = useState<string | null>(null)
    const [members, setMembers] = useState<ClusterNode[]>([])
    const [selfNodeUuid, setSelfNodeUuid] = useState<string | null>(null)

    const applySettings = useCallback(
        (next: CloudProvidersSettings, preferredProviderId?: string) => {
            setSettings(next)
            setConnectionResults({})
            const provider =
                next.config.providers.find(entry => entry.id === preferredProviderId) ??
                next.config.providers[0]
            if (!provider) {
                setEditingProviderRefId('')
                setProviderId('')
                setProviderKind('generic')
                setProviderEnabled(true)
                setBaseUrl('')
                setUpstreamModel('')
                setPublicModelName('')
                setCapabilitiesText('chat, streaming')
                return
            }
            setEditingProviderRefId(provider.id)
            setProviderId(provider.id)
            setProviderEnabled(provider.enabled)
            setProviderKind(provider.base_url.includes('api.deepseek.com') ? 'deepseek' : 'generic')
            setBaseUrl(provider.base_url)
            const model = provider.models[0]
            if (model) {
                setUpstreamModel(model.upstream_id)
                setPublicModelName(model.public_id.split('/').at(-1) ?? model.public_id)
                setCapabilitiesText(model.capabilities.join(', '))
            }
        },
        []
    )

    const reload = useCallback(async () => {
        if (!window.pairApi) return
        setError(null)
        try {
            applySettings(await window.pairApi.cloudProviders.getSettings())
        } catch (loadError) {
            setError(getErrorString(loadError))
        }
    }, [applySettings])

    useEffect(() => {
        void reload()
        if (!window.pairApi) return
        let mounted = true
        const reloadMembers = async () => {
            try {
                const snapshot = await window.pairApi.cluster.getInitial()
                if (mounted) {
                    setMembers(snapshot.members)
                    setSelfNodeUuid(snapshot.identity.nodeUuid)
                }
            } catch (loadError) {
                if (mounted) setError(getErrorString(loadError))
            }
        }
        void reloadMembers()
        const unsubscribeSettings = window.pairApi.cloudProviders.onSettingsChanged(applySettings)
        const unsubscribeMembers = window.pairApi.nodes.onMembersChanged(nodes => setMembers(nodes))
        return () => {
            mounted = false
            unsubscribeSettings()
            unsubscribeMembers()
        }
    }, [applySettings, reload])

    const policyItems: DropdownEntry[] = useMemo(
        () =>
            POLICY_VALUES.map(policy => ({
                children: POLICY_LABELS[policy],
                onSelect: () =>
                    setSettings(current => ({
                        ...current,
                        policy,
                        policyMigrationRequired: false
                    }))
            })),
        []
    )
    const fallbackModelItems: DropdownEntry[] = useMemo(
        () => [
            {
                children: 'No paid fallback model',
                onSelect: () => setSettings(current => ({ ...current, fallbackCloudModel: '' }))
            },
            ...settings.config.providers
                .filter(provider => provider.enabled)
                .flatMap(provider =>
                    provider.models.map(model => ({
                        children: `${provider.id} · ${model.public_id}`,
                        onSelect: () =>
                            setSettings(current => ({
                                ...current,
                                fallbackCloudModel: model.public_id
                            }))
                    }))
                )
        ],
        [settings.config.providers]
    )
    const fallbackModelAvailable = settings.config.providers.some(
        provider =>
            provider.enabled &&
            provider.models.some(model => model.public_id === settings.fallbackCloudModel)
    )
    const providerKindItems: DropdownEntry[] = useMemo(
        () => [
            {
                children: 'DeepSeek preset',
                onSelect: () => {
                    setProviderKind('deepseek')
                    if (!editingProviderRefId) setProviderId('deepseek-primary')
                    setBaseUrl('https://api.deepseek.com')
                }
            },
            {
                children: 'Generic OpenAI compatible',
                onSelect: () => {
                    setProviderKind('generic')
                    if (!editingProviderRefId) setProviderId('openai-compatible')
                    setBaseUrl('')
                }
            }
        ],
        [editingProviderRefId]
    )
    const providerItems: DropdownEntry[] = useMemo(
        () =>
            settings.config.providers.map(provider => ({
                children: provider.id,
                onSelect: () => {
                    setEditingProviderRefId(provider.id)
                    setProviderId(provider.id)
                    setProviderEnabled(provider.enabled)
                    setProviderKind(
                        provider.base_url.includes('api.deepseek.com') ? 'deepseek' : 'generic'
                    )
                    setBaseUrl(provider.base_url)
                    const model = provider.models[0]
                    setUpstreamModel(provider.models.map(entry => entry.upstream_id).join(', '))
                    setPublicModelName(
                        provider.models
                            .map(entry => entry.public_id.split('/').at(-1) ?? entry.public_id)
                            .join(', ')
                    )
                    setCapabilitiesText(model?.capabilities.join(', ') ?? 'chat')
                    setCredential('')
                }
            })),
        [settings.config.providers]
    )

    const addProvider = useCallback(() => {
        setEditingProviderRefId('')
        setProviderKind('generic')
        setProviderEnabled(true)
        setProviderId(`provider-${settings.config.providers.length + 1}`)
        setBaseUrl('')
        setUpstreamModel('')
        setPublicModelName('')
        setCapabilitiesText('chat, streaming')
        setCredential('')
        setError(null)
        setStatus(null)
    }, [settings.config.providers.length])

    const save = useCallback(async () => {
        if (!window.pairApi) return
        setBusy(true)
        setError(null)
        setStatus(null)
        const safeProviderId = providerId.trim()
        const upstreamModels = upstreamModel
            .split(',')
            .map(model => model.trim())
            .filter(Boolean)
        const publicModels = publicModelName
            .split(',')
            .map(model => model.trim())
            .filter(Boolean)
        const capabilities = capabilitiesText
            .split(',')
            .map(value => value.trim())
            .filter(Boolean)
        const draftProvider: CloudProviderConfig | null =
            safeProviderId && baseUrl.trim() && upstreamModels.length > 0 && publicModels.length > 0
                ? {
                      id: safeProviderId,
                      protocol: 'openai_chat_completions',
                      base_url: baseUrl.trim(),
                      auth_ref: `user-vault:${safeProviderId}`,
                      enabled: providerEnabled,
                      models: upstreamModels.map((model, index) => ({
                          public_id: `cloud/${safeProviderId}/${publicModels[index] ?? model}`,
                          upstream_id: model,
                          capabilities
                      }))
                  }
                : null
        const providers = editingProviderRefId
            ? settings.config.providers.flatMap(provider =>
                  provider.id === editingProviderRefId
                      ? draftProvider
                          ? [draftProvider]
                          : []
                      : [provider]
              )
            : draftProvider
              ? [draftProvider, ...settings.config.providers]
              : settings.config.providers
        const next: CloudProvidersSettings = {
            ...settings,
            config: {
                schema_version: 1,
                providers
            }
        }
        try {
            await window.pairApi.cloudProviders.saveSettings(next)
            applySettings(next, draftProvider?.id)
            if (credential) {
                const result = await window.pairApi.cloudProviders.setCredential(
                    `user-vault:${safeProviderId}`,
                    credential
                )
                if (!result.credentialConfigured) throw new Error('Provider key was not stored')
                setCredential('')
            }
            setStatus('Cloud provider settings saved.')
        } catch (saveError) {
            setError(getErrorString(saveError))
        } finally {
            setBusy(false)
        }
    }, [
        applySettings,
        baseUrl,
        capabilitiesText,
        credential,
        editingProviderRefId,
        providerEnabled,
        providerId,
        publicModelName,
        settings,
        upstreamModel
    ])

    const testConnection = useCallback(async () => {
        if (!window.pairApi) return
        if (
            !window.confirm(
                'Send an authenticated GET request to the provider model-list endpoint? This does not run inference.'
            )
        )
            return
        setTesting(true)
        setError(null)
        setStatus(null)
        try {
            const result = await window.pairApi.cloudProviders.testConnection(providerId.trim())
            setConnectionResults(current => ({ ...current, [providerId.trim()]: result.connected }))
            setStatus(
                result.connected ? 'Provider connection succeeded.' : 'Provider connection failed.'
            )
        } catch (testError) {
            setError(getErrorString(testError))
        } finally {
            setTesting(false)
        }
    }, [providerId])

    const removeProvider = useCallback(async () => {
        if (!window.pairApi || !editingProviderRefId) return
        if (!window.confirm(`Remove provider ${editingProviderRefId} and its saved credential?`))
            return
        setBusy(true)
        setError(null)
        setStatus(null)
        const next: CloudProvidersSettings = {
            ...settings,
            config: {
                schema_version: 1,
                providers: settings.config.providers.filter(
                    provider => provider.id !== editingProviderRefId
                )
            }
        }
        try {
            await window.pairApi.cloudProviders.saveSettings(next)
            applySettings(next)
            setStatus('Provider removed.')
        } catch (removeError) {
            setError(getErrorString(removeError))
        } finally {
            setBusy(false)
        }
    }, [applySettings, editingProviderRefId, settings])

    const setNodeAuthorization = useCallback(
        async (node: ClusterNode, authorized: boolean) => {
            if (!window.pairApi) return
            if (authorized && !node.certFingerprint) return
            if (
                authorized &&
                !window.confirm(
                    `Allow ${node.name} to use this host's configured paid Cloud providers?`
                )
            )
                return
            setBusy(true)
            setError(null)
            const authorizedNodes = settings.authorizedNodes.filter(
                entry => entry.nodeUuid !== node.nodeUuid
            )
            if (authorized && node.certFingerprint) {
                authorizedNodes.push({
                    nodeUuid: node.nodeUuid,
                    certFingerprint: node.certFingerprint
                })
            }
            const next = { ...settings, authorizedNodes }
            try {
                await window.pairApi.cloudProviders.saveSettings(next)
                applySettings(next)
                setStatus(
                    authorized
                        ? `${node.name} can use paid Cloud providers.`
                        : `${node.name}'s Cloud access was revoked.`
                )
            } catch (saveError) {
                setError(getErrorString(saveError))
            } finally {
                setBusy(false)
            }
        },
        [applySettings, settings]
    )

    const pairedMembers = members.filter(
        member => member.state === 'member' && member.nodeUuid !== selfNodeUuid
    )
    const configuredProviders = settings.config.providers
    const enabledProviders = configuredProviders.filter(provider => provider.enabled).length

    return (
        <details className="pair-paper w-full p-4 cloud-providers-card">
            <summary className="pair-accordion-summary cloud-providers-card-summary">
                <Flex align="center" gap="2">
                    <ExpandMore className="cloud-providers-card-chevron" style={{ fontSize: 14 }} />
                    <Text kind="body/semibold/md">Cloud providers</Text>
                </Flex>
                <Flex align="center" gap="3" wrap="wrap">
                    <Text kind="body/regular/sm" className="text-subtle-color">
                        {configuredProviders.length} configured · {enabledProviders} enabled ·{' '}
                        {settings.cloudEnabled ? 'routing enabled' : 'routing disabled'}
                    </Text>
                </Flex>
            </summary>
            <Stack gap="4" className="pt-4">
                <Text kind="body/regular/sm" className="text-subtle-color">
                    Cloud routing is off by default. Saving settings does not contact a provider.
                </Text>
                {configuredProviders.length > 0 && (
                    <Stack gap="2" aria-label="Configured cloud providers">
                        {configuredProviders.map(provider => {
                            const connectionResult = connectionResults[provider.id]
                            const modelCount = provider.models.length
                            return (
                                <Flex
                                    key={provider.id}
                                    align="center"
                                    justify="between"
                                    gap="3"
                                    wrap="wrap"
                                >
                                    <Text kind="body/semibold/sm">{provider.id}</Text>
                                    <Text kind="body/regular/sm" className="text-subtle-color">
                                        {provider.enabled ? 'Enabled' : 'Disabled'} · {modelCount}{' '}
                                        {modelCount === 1 ? 'model' : 'models'} ·{' '}
                                        {connectionResult === undefined
                                            ? 'Not tested this session'
                                            : connectionResult
                                              ? 'Connection test succeeded'
                                              : 'Connection test failed'}
                                    </Text>
                                </Flex>
                            )
                        })}
                    </Stack>
                )}
                <Stack gap="4">
                    <Flex align="center" justify="between" gap="4" wrap="wrap">
                        <Stack gap="1">
                            <Text kind="body/semibold/md">Cloud routing</Text>
                            <Text kind="body/regular/sm" className="text-subtle-color">
                                Cloud routing is off by default. Saving settings does not contact a
                                provider.
                            </Text>
                        </Stack>
                        <Switch
                            size="small"
                            checked={settings.cloudEnabled}
                            onCheckedChange={cloudEnabled => {
                                if (
                                    cloudEnabled &&
                                    !window.confirm(
                                        'Cloud requests can incur provider charges. Enable cloud routing?'
                                    )
                                )
                                    return
                                setSettings(current => ({ ...current, cloudEnabled }))
                            }}
                            aria-label="Enable cloud routing"
                        />
                    </Flex>

                    <Stack gap="2">
                        {settings.policyMigrationRequired && (
                            <InlineErrorBanner
                                severity="warning"
                                message="The previous Prefer cloud policy has been retired. Choose and save a routing policy; requests currently use Local only until you confirm."
                            />
                        )}
                        <Text kind="body/semibold/sm">Paired node Cloud access</Text>
                        <Text kind="body/regular/sm" className="text-subtle-color">
                            Pairing does not grant access to this host&apos;s paid Cloud account.
                            Authorize each node separately.
                        </Text>
                        {pairedMembers.map(member => (
                            <Flex key={member.nodeUuid} align="center" justify="between" gap="3">
                                <Text kind="body/regular/sm">{member.name}</Text>
                                <Switch
                                    size="small"
                                    checked={settings.authorizedNodes.some(
                                        entry =>
                                            entry.nodeUuid === member.nodeUuid &&
                                            entry.certFingerprint === member.certFingerprint
                                    )}
                                    disabled={
                                        busy ||
                                        settings.policyMigrationRequired ||
                                        !member.certFingerprint
                                    }
                                    onCheckedChange={authorized =>
                                        void setNodeAuthorization(member, authorized)
                                    }
                                    aria-label={`Allow ${member.name} to use paid Cloud providers`}
                                />
                            </Flex>
                        ))}
                        {pairedMembers.length === 0 && (
                            <Text kind="body/regular/sm" className="text-subtle-color">
                                No paired nodes are available.
                            </Text>
                        )}
                    </Stack>

                    <Flex align="center" justify="between" gap="4" wrap="wrap">
                        <Text kind="body/regular/sm">Provider enabled</Text>
                        <Switch
                            size="small"
                            checked={providerEnabled}
                            onCheckedChange={setProviderEnabled}
                            aria-label="Enable this cloud provider"
                        />
                    </Flex>
                    <Flex align="center" justify="between" gap="4" wrap="wrap">
                        <Text kind="body/regular/sm">
                            Allow paid cloud fallback when preferring local
                        </Text>
                        <Switch
                            size="small"
                            checked={settings.allowPaidFallback}
                            onCheckedChange={allowPaidFallback => {
                                if (
                                    allowPaidFallback &&
                                    !window.confirm(
                                        'A fallback request may incur provider charges. Allow paid fallback?'
                                    )
                                )
                                    return
                                setSettings(current => ({ ...current, allowPaidFallback }))
                            }}
                            aria-label="Allow paid cloud fallback"
                        />
                    </Flex>

                    {error && <InlineErrorBanner severity="error" message={error} />}
                    {status && <Text kind="body/regular/sm">{status}</Text>}

                    <Flex wrap="wrap" gap="4">
                        <FormField slotLabel="Configured provider">
                            <Dropdown
                                items={providerItems}
                                size="small"
                                aria-label="Select configured provider"
                                disabled={providerItems.length === 0}
                            >
                                {editingProviderRefId || 'No providers configured'}
                            </Dropdown>
                        </FormField>
                        <Flex align="end" gap="2">
                            <Button
                                kind="secondary"
                                size="small"
                                onClick={addProvider}
                                disabled={busy || settings.policyMigrationRequired}
                            >
                                Add provider
                            </Button>
                            <Button
                                kind="secondary"
                                size="small"
                                onClick={() => void removeProvider()}
                                disabled={
                                    busy ||
                                    settings.policyMigrationRequired ||
                                    !editingProviderRefId
                                }
                            >
                                Remove provider
                            </Button>
                        </Flex>
                        <FormField slotLabel="Provider type">
                            <Dropdown
                                items={providerKindItems}
                                size="small"
                                aria-label="Select provider type"
                            >
                                {providerKind === 'deepseek'
                                    ? 'DeepSeek preset'
                                    : 'Generic OpenAI compatible'}
                            </Dropdown>
                        </FormField>
                        <FormField slotLabel="Provider ID">
                            <TextInput
                                size="small"
                                value={providerId}
                                onValueChange={setProviderId}
                            />
                        </FormField>
                        <FormField slotLabel="API base URL">
                            <TextInput size="small" value={baseUrl} onValueChange={setBaseUrl} />
                        </FormField>
                        <FormField slotLabel="Provider model IDs (comma separated)">
                            <TextInput
                                size="small"
                                value={upstreamModel}
                                onValueChange={setUpstreamModel}
                            />
                        </FormField>
                        <FormField slotLabel="PAIR model names (comma separated)">
                            <TextInput
                                size="small"
                                value={publicModelName}
                                onValueChange={setPublicModelName}
                            />
                        </FormField>
                        <FormField slotLabel="Supported capabilities (comma separated)">
                            <TextInput
                                size="small"
                                value={capabilitiesText}
                                onValueChange={setCapabilitiesText}
                                placeholder="chat, streaming, tools, json_object, json_schema, vision"
                            />
                        </FormField>
                        <FormField slotLabel="Provider API key">
                            <TextInput
                                size="small"
                                type="password"
                                value={credential}
                                onValueChange={setCredential}
                                autoComplete="new-password"
                                placeholder="Leave blank to keep the saved key"
                            />
                        </FormField>
                        <FormField slotLabel="Routing policy">
                            <Dropdown
                                items={policyItems}
                                size="small"
                                aria-label="Select cloud routing policy"
                            >
                                {settings.policyMigrationRequired
                                    ? 'Choose a routing policy'
                                    : POLICY_LABELS[settings.policy]}
                            </Dropdown>
                        </FormField>
                        <Stack gap="1">
                            <FormField slotLabel="Paid fallback model">
                                <Dropdown
                                    items={fallbackModelItems}
                                    size="small"
                                    aria-label="Select paid Cloud fallback model"
                                >
                                    {settings.fallbackCloudModel === ''
                                        ? 'No model selected'
                                        : fallbackModelAvailable
                                          ? settings.fallbackCloudModel
                                          : `Unavailable: ${settings.fallbackCloudModel}`}
                                </Dropdown>
                            </FormField>
                            <Text kind="body/regular/sm" className="text-subtle-color">
                                Only this selected model is used for automatic Cloud routing. A
                                fallback request may incur provider charges.
                            </Text>
                            {settings.fallbackCloudModel !== '' && !fallbackModelAvailable && (
                                <InlineErrorBanner
                                    severity="warning"
                                    message="The selected fallback model is unavailable. Choose an enabled model or clear the selection; automatic routing will not switch to another provider."
                                />
                            )}
                        </Stack>
                        <FormField slotLabel="Monthly budget (USD)">
                            <TextInput
                                size="small"
                                inputMode="decimal"
                                value={String(settings.monthlyBudgetUSD)}
                                onValueChange={value =>
                                    setSettings(current => ({
                                        ...current,
                                        monthlyBudgetUSD: Number(value) || 0
                                    }))
                                }
                            />
                        </FormField>
                        <FormField slotLabel="Per-request maximum (USD)">
                            <TextInput
                                size="small"
                                inputMode="decimal"
                                value={String(settings.perRequestMaxEstimatedCostUSD)}
                                onValueChange={value =>
                                    setSettings(current => ({
                                        ...current,
                                        perRequestMaxEstimatedCostUSD: Number(value) || 0
                                    }))
                                }
                            />
                        </FormField>
                    </Flex>

                    <Flex align="center" justify="between" gap="3" wrap="wrap">
                        <Text kind="body/regular/sm" className="text-subtle-color">
                            Public model:{' '}
                            {providerId
                                ? `cloud/${providerId}/${publicModelName}`
                                : 'not configured'}
                        </Text>
                        <Flex gap="2">
                            <Button
                                kind="secondary"
                                size="small"
                                onClick={() => void testConnection()}
                                disabled={
                                    busy ||
                                    testing ||
                                    !settings.config.providers.some(
                                        provider => provider.id === providerId.trim()
                                    )
                                }
                            >
                                {testing ? 'Testing…' : 'Test connection'}
                            </Button>
                            <Button
                                kind="secondary"
                                size="small"
                                onClick={() => void reload()}
                                disabled={busy}
                            >
                                Reload
                            </Button>
                            <Button
                                kind="primary"
                                color="brand"
                                size="small"
                                onClick={() => void save()}
                                disabled={busy || settings.policyMigrationRequired}
                            >
                                Save provider settings
                            </Button>
                        </Flex>
                    </Flex>
                </Stack>
            </Stack>
        </details>
    )
}
