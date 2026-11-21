import { createAsyncThunk, createSlice } from '@reduxjs/toolkit'
import { login } from '../../services/authService'
import { useMockApi } from '../../services/api/apiConfig'

const STORAGE_KEY = 'somnisense.mock-user'

function getStoredUser() {
  if (!useMockApi) return null
  try {
    const storedUser = sessionStorage.getItem(STORAGE_KEY)
    return storedUser ? JSON.parse(storedUser) : null
  } catch (error) {
    console.error('Unable to restore the saved mock session.', error)
    return null
  }
}

export const signIn = createAsyncThunk(
  'auth/signIn',
  async (credentials, { rejectWithValue }) => {
    try {
      const user = await login(credentials)
      if (useMockApi) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user))
      return user
    } catch (error) {
      return rejectWithValue(error.message || 'Unable to sign in. Please try again.')
    }
  },
)

const authSlice = createSlice({
  name: 'auth',
  initialState: {
    user: getStoredUser(),
    status: 'idle',
    error: null,
    sessionExpired: false,
  },
  reducers: {
    signedOut(state) {
      state.user = null
      state.status = 'idle'
      state.error = null
      state.sessionExpired = false
    },
    sessionExpired(state) {
      state.user = null
      state.status = 'idle'
      state.error = null
      state.sessionExpired = true
    },
  },
  extraReducers(builder) {
    builder
      .addCase(signIn.pending, (state) => {
        state.status = 'loading'
        state.error = null
        state.sessionExpired = false
      })
      .addCase(signIn.fulfilled, (state, action) => {
        state.user = action.payload
        state.status = 'authenticated'
        state.error = null
      })
      .addCase(signIn.rejected, (state, action) => {
        state.status = 'idle'
        state.error = action.payload || action.error.message || 'Unable to sign in.'
      })
  },
})

export const { signedOut, sessionExpired } = authSlice.actions
export const selectAuth = (state) => state.auth
export default authSlice.reducer
export { STORAGE_KEY }
