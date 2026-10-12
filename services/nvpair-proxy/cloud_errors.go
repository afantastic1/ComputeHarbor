// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"errors"
	"net"
)

type cloudErrorCode string

const (
	cloudErrUnavailable       cloudErrorCode = "cloud_not_available"
	cloudErrInvalidCredential cloudErrorCode = "invalid_provider_credential"
	cloudErrProviderRejected  cloudErrorCode = "provider_rejected_request"
	cloudErrInvalidRequest    cloudErrorCode = "invalid_request"
	cloudErrInvalidEndpoint   cloudErrorCode = "invalid_provider_endpoint"
	cloudErrProviderTimeout   cloudErrorCode = "provider_timeout"
	cloudErrProviderCanceled  cloudErrorCode = "provider_canceled"
	cloudErrProviderError     cloudErrorCode = "provider_error"
	cloudErrResponseTooLarge  cloudErrorCode = "provider_response_too_large"
	cloudErrResponseRead      cloudErrorCode = "provider_response_read_error"
	cloudErrResponseClose     cloudErrorCode = "provider_response_close_error"
	cloudErrStreamTruncated   cloudErrorCode = "provider_stream_truncated"
	cloudErrSSEEventTooLarge  cloudErrorCode = "provider_sse_event_too_large"
)

type cloudAdapterError struct {
	code  cloudErrorCode
	cause error
}

func (e *cloudAdapterError) Error() string {
	switch e.code {
	case cloudErrUnavailable:
		return "cloud provider target is unavailable"
	case cloudErrInvalidCredential:
		return "provider credential is invalid"
	case cloudErrInvalidRequest:
		return "chat request is invalid"
	case cloudErrInvalidEndpoint:
		return "provider endpoint is invalid"
	case cloudErrProviderTimeout:
		return "provider request timed out"
	case cloudErrProviderCanceled:
		return "provider request canceled"
	case cloudErrResponseTooLarge:
		return "provider response exceeded the configured size limit"
	case cloudErrResponseRead:
		return "provider response could not be read"
	case cloudErrResponseClose:
		return "provider response could not be closed"
	case cloudErrStreamTruncated:
		return "provider SSE stream ended before its terminal event (unexpected EOF)"
	case cloudErrSSEEventTooLarge:
		return "provider SSE event exceeded the configured size limit"
	default:
		return "provider request failed"
	}
}

func (e *cloudAdapterError) Unwrap() error { return e.cause }

func newCloudAdapterError(code cloudErrorCode, cause error) error {
	return &cloudAdapterError{code: code, cause: cause}
}

func cloudErrorCodeOf(err error) (cloudErrorCode, bool) {
	var adapterError *cloudAdapterError
	if !errors.As(err, &adapterError) {
		return "", false
	}
	return adapterError.code, true
}

func cloudTransportError(err error) error {
	var networkError net.Error
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return newCloudAdapterError(cloudErrProviderTimeout, err)
	case errors.Is(err, context.Canceled):
		return newCloudAdapterError(cloudErrProviderCanceled, err)
	case errors.As(err, &networkError) && networkError.Timeout():
		return newCloudAdapterError(cloudErrProviderTimeout, err)
	default:
		return newCloudAdapterError(cloudErrProviderError, err)
	}
}
