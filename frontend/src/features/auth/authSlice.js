import { createAsyncThunk, createSlice } from '@reduxjs/toolkit'
import {
  getCurrentUser,
  login,
  logout,
  MOCK_SESSION_STORAGE_KEY,
} from '../../services/authService'
import { useMockApi } from '../../services/api/apiConfig'

function getInitialMockUser() {
  if (!useMockApi) return null
  try {
    const storedUser = sessionStorage.getItem(MOCK_SESSION_STORAGE_KEY)
    return storedUser ? JSON.parse(storedUser) : null
  } catch (error) {
    console.error('Unable to restore the saved mock session.', error)
    return null
  }
}

export const restoreSession = createAsyncThunk(
  'auth/restoreSession',
  async (_, { rejectWithValue }) => {
    try {
      return await getCurrentUser()
    } catch (error) {
      if (error?.status === 401) {
        return rejectWithValue({
          code: error.code || 'UNAUTHORIZED',
          message: error.message,
        })
      }
      return rejectWithValue({
        code: error?.code || 'RESTORE_FAILED',
        message: error?.message || 'Unable to verify current session.',
      })
    }
  },
)

export const signIn = createAsyncThunk(
  'auth/signIn',
  async (credentials, { rejectWithValue }) => {
    try {
      return await login(credentials)
    } catch (error) {
      return rejectWithValue(error.message || 'Unable to sign in. Please try again.')
    }
  },
)

export const signOutUser = createAsyncThunk(
  'auth/signOutUser',
  async () => {
    try {
      await logout()
    } catch (error) {
      console.error('Logout request failed; clearing client session.', error)
    }
  },
)

const authSlice = createSlice({
  name: 'auth',
  initialState: {
    user: getInitialMockUser(),
    status: 'idle',
    initialized: useMockApi,
    error: null,
    sessionExpired: false,
    sessionInvalidReason: null,
  },
  reducers: {
    signedOut(state) {
      state.user = null
      state.status = 'idle'
      state.initialized = true
      state.error = null
      state.sessionExpired = false
      state.sessionInvalidReason = null
    },
    sessionExpired(state, action) {
      state.user = null
      state.status = 'idle'
      state.initialized = true
      state.error = null
      state.sessionExpired = true
      state.sessionInvalidReason = action.payload?.code || 'UNAUTHORIZED'
    },
  },
  extraReducers(builder) {
    builder
      .addCase(restoreSession.pending, (state) => {
        if (!state.initialized) {
          state.status = 'loading'
        }
      })
      .addCase(restoreSession.fulfilled, (state, action) => {
        state.user = action.payload
        state.status = action.payload ? 'authenticated' : 'idle'
        state.initialized = true
        state.error = null
      })
      .addCase(restoreSession.rejected, (state, action) => {
        state.user = null
        state.status = 'idle'
        state.initialized = true
        if (action.payload?.code === 'SESSION_INVALID') {
          state.sessionExpired = true
          state.sessionInvalidReason = 'SESSION_INVALID'
        }
      })
      .addCase(signIn.pending, (state) => {
        state.status = 'loading'
        state.error = null
        state.sessionExpired = false
        state.sessionInvalidReason = null
      })
      .addCase(signIn.fulfilled, (state, action) => {
        state.user = action.payload
        state.status = 'authenticated'
        state.initialized = true
        state.error = null
        state.sessionExpired = false
        state.sessionInvalidReason = null
      })
      .addCase(signIn.rejected, (state, action) => {
        state.status = 'idle'
        state.initialized = true
        state.error = action.payload || action.error.message || 'Unable to sign in.'
      })
      .addCase(signOutUser.fulfilled, (state) => {
        state.user = null
        state.status = 'idle'
        state.initialized = true
        state.error = null
        state.sessionExpired = false
        state.sessionInvalidReason = null
      })
  },
})

export const { signedOut, sessionExpired } = authSlice.actions
export const selectAuth = (state) => state.auth
export default authSlice.reducer
export { MOCK_SESSION_STORAGE_KEY as STORAGE_KEY }
