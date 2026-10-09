package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// openAICompatible talks to any server that implements the OpenAI chat-completions
// request shape: OpenAI itself, and a local Ollama, where nothing leaves the machine.
type openAICompatible struct {
	name    string
	baseURL string
	apiKey  string
	model   string
	http    *http.Client
}

const (
	DefaultOpenAIBaseURL = "https://api.openai.com/v1"
	maxProviderBody      = 1 << 20
)

// NewOpenAICompatible returns a provider named name ("openai" or "ollama").
func NewOpenAICompatible(name, baseURL, apiKey, model string) Provider {
	if baseURL == "" {
		baseURL = DefaultOpenAIBaseURL
	}
	return &openAICompatible{
		name: name, baseURL: strings.TrimRight(baseURL, "/"), apiKey: apiKey, model: model,
		http: &http.Client{Timeout: providerTimeout},
	}
}

func (p *openAICompatible) Name() string  { return p.name }
func (p *openAICompatible) Model() string { return p.model }

func (p *openAICompatible) Analyze(ctx context.Context, req Request) (Response, error) {
	body, err := json.Marshal(map[string]any{
		"model": p.model,
		"messages": []map[string]string{
			{"role": "system", "content": req.System},
			{"role": "user", "content": req.User},
		},
		"response_format": map[string]any{
			"type":        "json_schema",
			"json_schema": map[string]any{"name": "analysis", "strict": true, "schema": req.Schema},
		},
	})
	if err != nil {
		return Response{}, err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, p.baseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return Response{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if p.apiKey != "" {
		httpReq.Header.Set("Authorization", "Bearer "+p.apiKey)
	}
	resp, err := p.http.Do(httpReq)
	if err != nil {
		return Response{}, providerError("%s: request failed", p.name)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxProviderBody))
	if err != nil {
		return Response{}, providerError("%s: reading the answer failed", p.name)
	}
	if resp.StatusCode != http.StatusOK {
		return Response{}, providerError("%s: HTTP %d", p.name, resp.StatusCode)
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string  `json:"content"`
				Refusal *string `json:"refusal"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int64 `json:"prompt_tokens"`
			CompletionTokens int64 `json:"completion_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || len(out.Choices) == 0 {
		return Response{}, providerError("%s: unexpected answer", p.name)
	}
	usage := Response{InputTokens: out.Usage.PromptTokens, OutputTokens: out.Usage.CompletionTokens}
	choice := out.Choices[0]
	if choice.Message.Refusal != nil && *choice.Message.Refusal != "" {
		return usage, providerError("%s: declined", p.name)
	}
	if choice.FinishReason != "stop" {
		return usage, providerError("%s: stopped with %q", p.name, choice.FinishReason)
	}
	usage.JSON = choice.Message.Content
	return usage, nil
}
