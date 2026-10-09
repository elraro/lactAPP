package ai

import "fmt"

// Settings is the operator's choice of provider (BT_AI_* variables).
type Settings struct {
	Provider string
	Model    string
	APIKey   string
	BaseURL  string
}

// NewProvider builds the configured provider, or returns nil when none is configured:
// AI then does not exist on this server.
func NewProvider(s Settings) (Provider, error) {
	switch s.Provider {
	case "":
		return nil, nil
	case "anthropic":
		if s.APIKey == "" {
			return nil, fmt.Errorf("BT_AI_API_KEY: required for provider anthropic")
		}
		return NewAnthropic(s.APIKey, s.Model, s.BaseURL), nil
	case "openai":
		if s.APIKey == "" || s.Model == "" {
			return nil, fmt.Errorf("BT_AI_API_KEY and BT_AI_MODEL: required for provider openai")
		}
		return NewOpenAICompatible("openai", s.BaseURL, s.APIKey, s.Model), nil
	case "ollama":
		if s.BaseURL == "" || s.Model == "" {
			return nil, fmt.Errorf("BT_AI_BASE_URL and BT_AI_MODEL: required for provider ollama")
		}
		return NewOpenAICompatible("ollama", s.BaseURL, s.APIKey, s.Model), nil
	default:
		return nil, fmt.Errorf("BT_AI_PROVIDER: must be anthropic, openai, ollama or empty")
	}
}
