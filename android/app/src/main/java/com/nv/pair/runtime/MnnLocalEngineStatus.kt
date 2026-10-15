/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnEngineState
import com.nv.pair.mnn.MnnErrorCode

data class MnnLocalEngineStatus(
    val available: Boolean,
    val backend: MnnBackend? = null,
    val errorCode: MnnErrorCode? = null,
    val engineState: MnnEngineState? = null,
    val modelId: String? = null,
)
