package ai

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

// DefaultAnthropicModel is used when BT_AI_MODEL is not set.
const DefaultAnthropicModel = "claude-opus-5-5"

const (
	// Generous for a short answer: the limit also covers the model's own reasoning.
	anthropicMaxTokens = 16000
	providerTimeout    = 2 * time.Minute
)

// Models on which a request declined by a safety classifier can be re-served by another
// model inside the same call (server-side fallback, beta). Other models reject the field.
var serverSideFallbackModels = map[string]bool{
	"claude-opus-5-5": true, "claude-opus-5": true, "claude-fable-5-1": true, "claude-sonnet-5-5": true,
}

type anthropicProvider struct {
	client anthropic.Client
	model  string
}

// NewAnthropic returns a provider for the Claude API. baseURL is only for tests.
func NewAnthropic(apiKey, model, baseURL string) Provider {
	if model == "" {
		model = DefaultAnthropicModel
	}
	opts := []option.RequestOption{option.WithAPIKey(apiKey), option.WithRequestTimeout(providerTimeout)}
	if baseURL != "" {
		opts = append(opts, option.WithBaseURL(baseURL))
	}
	return &anthropicProvider{client: anthropic.NewClient(opts...), model: model}
}

func (p *anthropicProvider) Name() string  { return "anthropic" }
func (p *anthropicProvider) Model() string { return p.model }

func (p *anthropicProvider) Analyze(ctx context.Context, req Request) (Response, error) {
	params := anthropic.BetaMessageNewParams{
		Model:     anthropic.Model(p.model),
		MaxTokens: anthropicMaxTokens,
		System:    []anthropic.BetaTextBlockParam{{Text: req.System}},
		Messages: []anthropic.BetaMessageParam{
			anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(req.User)),
		},
		// Structured output: the answer is constrained to the schema.
		OutputConfig: anthropic.BetaOutputConfigParam{Format: anthropic.BetaJSONOutputFormatParam{Schema: req.Schema}},
	}
	if serverSideFallbackModels[p.model] {
		params.Betas = []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01}
		params.Fallbacks = anthropic.BetaFallbacksParamUnion{OfDefault: constant.ValueOf[constant.Default]()}
	}

	msg, err := p.client.Beta.Messages.New(ctx, params)
	if err != nil {
		var apiErr *anthropic.Error
		if errors.As(err, &apiErr) {
			// Status and type only: the body may echo parts of the request.
			return Response{}, providerError("anthropic: HTTP %d (%s)", apiErr.StatusCode, apiErr.Type())
		}
		return Response{}, providerError("anthropic: request failed")
	}
	usage := Response{InputTokens: msg.Usage.InputTokens, OutputTokens: msg.Usage.OutputTokens}
	switch msg.StopReason {
	case anthropic.BetaStopReasonEndTurn:
	case anthropic.BetaStopReasonRefusal:
		return usage, providerError("anthropic: declined (%s)", msg.StopDetails.Category)
	default:
		// max_tokens and anything else would leave incomplete JSON.
		return usage, providerError("anthropic: stopped with %q", msg.StopReason)
	}
	var text strings.Builder
	for _, block := range msg.Content {
		if t, ok := block.AsAny().(anthropic.BetaTextBlock); ok {
			text.WriteString(t.Text)
		}
	}
	usage.JSON = text.String()
	return usage, nil
}
