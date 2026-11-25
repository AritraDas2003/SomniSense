const configuredMockMode = import.meta.env?.VITE_USE_MOCK_API

export const useMockApi = configuredMockMode === undefined || configuredMockMode === 'true'
export const apiBaseUrl = import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
