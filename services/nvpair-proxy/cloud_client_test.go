// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCloudClientUsesProviderCredentialAndPreservesRequestFields(t *testing.T) {
	var received atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received.Add(1)
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("upstream path = %q", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer provider-secret" {
			t.Errorf("provider authorization = %q", got)
		}
		for _, header := range []string{"Cookie", "Proxy-Authorization", "Connection"} {
			if got := r.Header.Get(header); got != "" {
				t.Errorf("forwarded forbidden header %s=%q", header, got)
			}
		}
		var payload map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode forwarded request: %v", err)
		}
		var model string
		if err := json.Unmarshal(payload["model"], &model); err != nil || model != "deepseek-chat" {
			t.Errorf("upstream model=%q err=%v", model, err)
		}
		for _, key := range []string{"tools", "tool_choice", "response_format", "stream_options", "messages"} {
			if len(payload[key]) == 0 {
				t.Errorf("request field %q was dropped", key)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[]}`)
	}))
	defer server.Close()

	provider := cloudProviderRuntime{ID: "deepseek-primary", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/deepseek/deepseek-chat", ProviderID: provider.ID, UpstreamID: "deepseek-chat", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	request := `{"model":"cloud/deepseek/deepseek-chat","messages":[{"role":"user","content":"hi"},{"role":"assistant","tool_calls":[{"id":"call_1"}]},{"role":"tool","tool_call_id":"call_1","content":"ok"}],"tools":[{"type":"function"}],"tool_choice":"required","response_format":{"type":"json_object"},"stream_options":{"include_usage":true},"stream":false}`

	response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "provider-secret", []byte(request))
	if err != nil {
		t.Fatalf("DoChat: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("upstream status=%d", response.StatusCode)
	}
	if got := received.Load(); got != 1 {
		t.Fatalf("upstream request count=%d, want 1", got)
	}
}

func TestCloudClientUsesOneVersionPathAndDoesNotRetryPOST(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden, http.StatusTooManyRequests, http.StatusInternalServerError, http.StatusServiceUnavailable} {
		t.Run(strconv.Itoa(status), func(t *testing.T) {
			var hits atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				hits.Add(1)
				if r.URL.Path != "/gateway/v1/chat/completions" {
					t.Errorf("upstream path=%q", r.URL.Path)
				}
				w.WriteHeader(status)
				_, _ = io.WriteString(w, `{"error":"provider response"}`)
			}))
			defer server.Close()
			provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL + "/gateway/v1/", Enabled: true}
			target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
			client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
			response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[]}`))
			if err != nil {
				t.Fatalf("DoChat returned transport error: %v", err)
			}
			body, readErr := io.ReadAll(response.Body)
			_ = response.Body.Close()
			if readErr != nil || response.StatusCode != status || hits.Load() != 1 || string(body) != `{"error":"provider response"}` {
				t.Fatalf("response status=%d body=%q readErr=%v upstream hits=%d", response.StatusCode, body, readErr, hits.Load())
			}
		})
	}
}

func TestCloudClientDoesNotReplayPOSTAfterUpstreamDisconnect(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = io.Copy(io.Discard, r.Body)
		connection, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Errorf("hijack upstream connection: %v", err)
			return
		}
		_ = connection.Close()
	}))
	defer server.Close()
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[]}`))
	if err == nil {
		t.Fatal("disconnected provider request unexpectedly succeeded")
	}
	if response != nil {
		t.Fatalf("DoChat returned response %#v alongside transport error %v", response, err)
	}
	if got := hits.Load(); got != 1 {
		t.Fatalf("upstream received %d POSTs after disconnect, want exactly one", got)
	}
}

func TestCloudClientBlocksPrivateDNSAnswersAndRedirects(t *testing.T) {
	privateLookups := atomic.Int32{}
	client := newCloudHTTPClient(cloudHTTPOptions{
		lookupIP: func(context.Context, string, string) ([]net.IPAddr, error) {
			privateLookups.Add(1)
			return []net.IPAddr{{IP: net.ParseIP("10.1.2.3")}}, nil
		},
	})
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: "https://provider.example", Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	if _, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[]}`)); err == nil {
		t.Fatal("private DNS result was allowed")
	} else if strings.Contains(err.Error(), "provider.example") {
		t.Fatalf("network error exposed provider URL: %v", err)
	}
	if privateLookups.Load() != 1 {
		t.Fatalf("DNS lookup count=%d, want one", privateLookups.Load())
	}

	var redirectHits atomic.Int32
	redirectTarget := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		redirectHits.Add(1)
	}))
	defer redirectTarget.Close()
	redirector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, redirectTarget.URL, http.StatusTemporaryRedirect)
	}))
	defer redirector.Close()
	loopbackClient := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	localProvider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: redirector.URL, Enabled: true}
	response, err := loopbackClient.DoChat(context.Background(), localProvider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[]}`))
	if err != nil {
		t.Fatalf("redirect response: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusTemporaryRedirect || redirectHits.Load() != 0 {
		t.Fatalf("redirect status=%d target hits=%d", response.StatusCode, redirectHits.Load())
	}
}

func TestCloudClientRejectsSpecialPurposeAddressRanges(t *testing.T) {
	for _, address := range []string{"127.0.0.1", "10.1.2.3", "169.254.169.254", "100.64.0.1", "198.18.0.1", "192.0.2.1", "2001:db8::1", "2002:0808:0808::1", "64:ff9b::808:808"} {
		if !isForbiddenCloudIP(net.ParseIP(address)) {
			t.Errorf("special-purpose IP %s was accepted", address)
		}
	}
	if isForbiddenCloudIP(net.ParseIP("8.8.8.8")) {
		t.Fatal("public IPv4 address was rejected")
	}
}

func TestCloudClientLimitsNonStreamingResponseBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, strings.Repeat("x", 32))
	}))
	defer server.Close()
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, maxResponseBytes: 16})
	response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[],"stream":false}`))
	if err == nil || !strings.Contains(err.Error(), "size limit") {
		t.Fatalf("oversized response error=%v", err)
	}
	if response != nil {
		t.Fatalf("DoChat returned response %#v alongside response size error %v", response, err)
	}
}

func TestCloudClientTimesOutStalledNonStreamingResponse(t *testing.T) {
	upstreamCanceled := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(upstreamCanceled)
	}))
	defer server.Close()
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, nonStreamTimeout: 25 * time.Millisecond})
	response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[],"stream":false}`))
	if err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("stalled response error=%v", err)
	}
	if response != nil {
		t.Fatalf("DoChat returned response %#v alongside timeout error %v", response, err)
	}
	select {
	case <-upstreamCanceled:
	case <-time.After(time.Second):
		t.Fatal("non-stream timeout did not cancel upstream")
	}
}

func TestCloudClientBoundsConcurrentStreamsAndReleasesSlots(t *testing.T) {
	var hits atomic.Int32
	secondUpstreamHit := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		var payload struct {
			Model string `json:"model"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode request: %v", err)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		if payload.Model == "upstream-one" {
			w.(http.Flusher).Flush()
			<-r.Context().Done()
			return
		}
		secondUpstreamHit <- struct{}{}
		_, _ = io.WriteString(w, "data: [DONE]\n\n")
	}))
	defer server.Close()
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, maxConcurrent: 1})
	firstTarget := cloudModelTarget{PublicID: "cloud/p/first", ProviderID: "p", UpstreamID: "upstream-one", Enabled: true}
	first, err := client.DoChat(context.Background(), provider, firstTarget, firstTarget.PublicID, "secret", []byte(`{"model":"cloud/p/first","messages":[],"stream":true}`))
	if err != nil {
		t.Fatalf("first DoChat: %v", err)
	}
	secondTarget := cloudModelTarget{PublicID: "cloud/p/second", ProviderID: "p", UpstreamID: "upstream-two", Enabled: true}
	secondDone := make(chan error, 1)
	secondCallStarted := make(chan struct{})
	go func() {
		close(secondCallStarted)
		response, requestErr := client.DoChat(context.Background(), provider, secondTarget, secondTarget.PublicID, "secret", []byte(`{"model":"cloud/p/second","messages":[],"stream":true}`))
		if requestErr == nil {
			_, requestErr = io.ReadAll(response.Body)
			_ = response.Body.Close()
		}
		secondDone <- requestErr
	}()
	<-secondCallStarted
	select {
	case <-secondUpstreamHit:
		t.Fatal("second stream reached upstream while the only slot was occupied")
	case <-time.After(30 * time.Millisecond):
	}
	if got := hits.Load(); got != 1 {
		t.Fatalf("second stream bypassed concurrency limit: upstream hits=%d", got)
	}
	_ = first.Body.Close()
	select {
	case err := <-secondDone:
		if err != nil {
			t.Fatalf("second DoChat after slot release: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("second request did not proceed after the first stream closed")
	}
	if got := hits.Load(); got != 2 {
		t.Fatalf("upstream hits=%d after slot release, want 2", got)
	}
}

func TestCloudClientBoundsTenAndThirtyConcurrentAgents(t *testing.T) {
	for _, count := range []int{10, 30} {
		t.Run(strconv.Itoa(count), func(t *testing.T) {
			var active atomic.Int32
			var maximum atomic.Int32
			var hits atomic.Int32
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				hits.Add(1)
				current := active.Add(1)
				for previous := maximum.Load(); current > previous && !maximum.CompareAndSwap(previous, current); previous = maximum.Load() {
				}
				time.Sleep(5 * time.Millisecond)
				active.Add(-1)
				w.Header().Set("Content-Type", "application/json")
				_, _ = io.WriteString(w, `{"choices":[]}`)
			}))
			defer server.Close()
			provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
			client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, maxConcurrent: 8})
			var workers sync.WaitGroup
			errors := make(chan error, count)
			for index := 0; index < count; index++ {
				workers.Add(1)
				go func(index int) {
					defer workers.Done()
					target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
					body := []byte(`{"model":"cloud/p/model","messages":[{"role":"user","content":"agent ` + strconv.Itoa(index) + `"}]}`)
					response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", body)
					if err == nil {
						_, err = io.Copy(io.Discard, response.Body)
						_ = response.Body.Close()
					}
					errors <- err
				}(index)
			}
			workers.Wait()
			close(errors)
			for err := range errors {
				if err != nil {
					t.Fatalf("Agent request failed: %v", err)
				}
			}
			if got := hits.Load(); got != int32(count) {
				t.Fatalf("upstream request count=%d, want %d", got, count)
			}
			if got := maximum.Load(); got > 8 {
				t.Fatalf("maximum concurrent upstream requests=%d, want <= 8", got)
			}
		})
	}
}

func TestCloudClientCancelsUpstreamWhenCallerCancels(t *testing.T) {
	started := make(chan struct{})
	upstreamCanceled := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
		close(upstreamCanceled)
	}))
	defer server.Close()
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	ctx, cancel := context.WithCancel(context.Background())
	response, err := client.DoChat(ctx, provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[],"stream":true}`))
	if err != nil {
		t.Fatalf("DoChat: %v", err)
	}
	defer response.Body.Close()
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("upstream request did not start")
	}
	done := make(chan error, 1)
	go func() {
		_, readErr := io.ReadAll(response.Body)
		done <- readErr
	}()
	cancel()
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "canceled") {
			t.Fatalf("cancelled request error=%v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("DoChat did not stop after caller cancellation")
	}
	select {
	case <-upstreamCanceled:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("caller cancellation did not reach the upstream")
	}
}

func TestCloudClientClassifiesResponseHeaderTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		time.Sleep(100 * time.Millisecond)
	}))
	defer server.Close()

	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, responseHeaderTimeout: 20 * time.Millisecond})

	_, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[]}`))
	if err == nil {
		t.Fatal("DoChat returned nil error after the response-header deadline")
	}
	code, ok := cloudErrorCodeOf(err)
	if !ok || code != cloudErrProviderTimeout {
		t.Fatalf("response-header timeout code=%q, ok=%v, want %q", code, ok, cloudErrProviderTimeout)
	}
	status, _, kind := mapCloudGatewayError(err)
	if status != http.StatusGatewayTimeout || kind != "provider_timeout" {
		t.Fatalf("Gateway timeout mapping=(%d, %q), want (%d, %q)", status, kind, http.StatusGatewayTimeout, "provider_timeout")
	}
}

func TestCloudClientPreservesSSEAndRejectsTruncatedOrOversizedEvents(t *testing.T) {
	for _, test := range []struct {
		name         string
		stream       string
		wantBody     string
		maxEventSize int
		wantError    string
	}{
		{
			name: "done", stream: "data: {\"model\":\"upstream\",\"choices\":[]}\n\ndata: [DONE]\n\n",
			wantBody: "data: {\"choices\":[],\"model\":\"cloud/p/model\"}\n\ndata: [DONE]\n\n", maxEventSize: 128,
		},
		{name: "missing terminal event", stream: "data: {\"choices\":[]}\n\n", maxEventSize: 128, wantError: io.ErrUnexpectedEOF.Error()},
		{name: "event size limit", stream: "data: " + strings.Repeat("x", 64) + "\n\n", maxEventSize: 24, wantError: "configured size limit"},
	} {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "text/event-stream")
				_, _ = io.WriteString(w, test.stream)
			}))
			defer server.Close()
			provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
			target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
			client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, maxSSEEventBytes: test.maxEventSize})
			response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[],"stream":true}`))
			if err != nil {
				t.Fatalf("DoChat: %v", err)
			}
			defer response.Body.Close()
			body, readErr := io.ReadAll(response.Body)
			if test.wantError != "" {
				if readErr == nil || !strings.Contains(readErr.Error(), test.wantError) {
					t.Fatalf("read error=%v, want %v", readErr, test.wantError)
				}
				return
			}
			wantBody := test.wantBody
			if wantBody == "" {
				wantBody = test.stream
			}
			if readErr != nil || string(body) != wantBody {
				t.Fatalf("stream body=%q err=%v", body, readErr)
			}
		})
	}
}

func TestProviderCloudUsageRequiresBothTokenCounts(t *testing.T) {
	if usage := providerCloudUsage([]byte(`{"usage":{"prompt_tokens":8}}`)); usage != nil {
		t.Fatalf("partial usage = %+v, want unknown", usage)
	}
	usage := providerCloudUsage([]byte(`{"usage":{"prompt_tokens":8,"completion_tokens":0}}`))
	if usage == nil || usage.InputTokens != 8 || usage.OutputTokens != 0 {
		t.Fatalf("complete usage = %+v, want input=8 output=0", usage)
	}
}

func TestCloudClientEndsAnIdleStream(t *testing.T) {
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()
	provider := cloudProviderRuntime{ID: "p", Protocol: "openai_chat_completions", BaseURL: server.URL, Enabled: true}
	target := cloudModelTarget{PublicID: "cloud/p/model", ProviderID: "p", UpstreamID: "upstream", Enabled: true}
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true, streamIdleTimeout: 30 * time.Millisecond})
	response, err := client.DoChat(context.Background(), provider, target, target.PublicID, "secret", []byte(`{"model":"cloud/p/model","messages":[],"stream":true}`))
	if err != nil {
		t.Fatalf("DoChat: %v", err)
	}
	defer response.Body.Close()
	<-started
	_, err = io.ReadAll(response.Body)
	if err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("idle stream error=%v", err)
	}
	var adapterError *cloudAdapterError
	if !errors.As(err, &adapterError) || adapterError.code != cloudErrProviderTimeout {
		code, _ := cloudErrorCodeOf(err)
		t.Fatalf("idle stream error type=%T code=%q", err, code)
	}
}

func TestCloudProviderConnectionCheckUsesModelsEndpointWithoutInference(t *testing.T) {
	var posts atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			posts.Add(1)
			t.Fatalf("connection check sent an inference POST")
		}
		if r.URL.Path != "/v1/models" {
			t.Errorf("models endpoint=%q", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Errorf("authorization=%q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"data":[]}`)
	}))
	defer server.Close()
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	provider := cloudProviderRuntime{ID: "test", BaseURL: server.URL, Enabled: true}
	if err := client.TestProvider(context.Background(), provider, "test-key"); err != nil {
		t.Fatalf("TestProvider: %v", err)
	}
	if got := posts.Load(); got != 0 {
		t.Fatalf("inference POST count=%d, want 0", got)
	}
}

func TestCloudProviderConnectionCheckDoesNotExposeProviderErrorBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"error":"secret provider response"}`)
	}))
	defer server.Close()
	client := newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	err := client.TestProvider(context.Background(), cloudProviderRuntime{ID: "test", BaseURL: server.URL}, "test-key")
	if err == nil || strings.Contains(err.Error(), "secret provider response") {
		t.Fatalf("provider test error=%v", err)
	}
}
