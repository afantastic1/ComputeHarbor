// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useId } from 'react'

interface BrandMarkProps {
    size?: number
    title?: string
    className?: string
}

export function BrandMark({ size = 20, title, className }: BrandMarkProps) {
    const titleId = useId()
    const accessibleProps = title
        ? { role: 'img', 'aria-labelledby': titleId }
        : { 'aria-hidden': true }

    return (
        <svg
            viewBox="0 0 32 32"
            width={size}
            height={size}
            fill="none"
            className={className}
            focusable="false"
            {...accessibleProps}
        >
            {title ? <title id={titleId}>{title}</title> : null}
            <path
                d="M4 5.5v13.25L12.25 27V14.5L4 5.5Z"
                fill="currentColor"
                opacity="0.72"
            />
            <path d="M28 5.5v13.25L19.75 27V14.5L28 5.5Z" fill="currentColor" />
            <path d="M14.25 8h3.5v15.5h-3.5z" fill="currentColor" />
            <path d="M14.25 25.5h3.5V29h-3.5z" fill="currentColor" opacity="0.55" />
        </svg>
    )
}
