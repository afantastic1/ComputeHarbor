// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Button, Flex, Stack, Text } from '@nvidia/foundations-react-core'
import { ContentCopy } from './icons'

import { useEngineStatusStore } from '@/ui/stores/engine-status.store'
import { EnabledEngineTypes } from '@/shared/constants/engines'
import { useCallback, useState } from 'react'
import { DismissibleTooltip } from '@/ui/components/DismissibleTooltip/DismissibleTooltip'
import { isEngineTypeRunningClusterWide } from '@/ui/utils/get-engines-for-node'
import { gatewayEndpointDisplayUrl } from '@/ui/utils/gateway-inference-paths'

export default function EndpointContent({ className }: { className?: string }) {
    const [copied, setCopied] = useState(false)
    const statusByNode = useEngineStatusStore(s => s.statusByNode)
    const gatewayAvailable = EnabledEngineTypes.some(type =>
        isEngineTypeRunningClusterWide(statusByNode, type)
    )
    const gatewayUrl = gatewayAvailable ? gatewayEndpointDisplayUrl() : null

    const handleCopy = useCallback(async (value: string) => {
        const electronCopy = window.windowApi?.window?.copyToClipboard

        const doTimer = () => {
            setCopied(true)
            setTimeout(() => setCopied(false), 1500)
        }

        if (typeof electronCopy === 'function') {
            try {
                await electronCopy(value)
                doTimer()
                return
            } catch {
                /* fall through */
            }
        }

        navigator.clipboard.writeText(value)
        doTimer()
    }, [])

    return (
        <Stack gap="4" className={`overflow-y-auto ${className ?? ''}`}>
            {!gatewayUrl && <Text kind="body/regular/sm">No engines are running</Text>}
            {gatewayUrl && (
                <Flex align="center" gap="4" justify="start" onClick={() => handleCopy(gatewayUrl)}>
                    <Stack className="grow min-w-0">
                        <Text kind="body/semibold/sm">ComputeHarbor OpenAI-compatible API</Text>
                        <Text kind="body/regular/sm" className="text-subtle-color truncate">
                            {gatewayUrl}
                        </Text>
                    </Stack>
                    <DismissibleTooltip slotContent="Copy" placement="right">
                        <Button kind="tertiary" size="small" aria-label="Copy ComputeHarbor API URL">
                            {copied ? (
                                <Text kind="body/regular/sm">Copied</Text>
                            ) : (
                                <ContentCopy style={{ fontSize: 16 }} />
                            )}
                        </Button>
                    </DismissibleTooltip>
                </Flex>
            )}
        </Stack>
    )
}
