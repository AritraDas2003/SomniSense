import { useCallback } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import { selectAuth, signIn, signedOut, STORAGE_KEY } from '../features/auth/authSlice'
import { useMockApi } from '../services/api/apiConfig'

export function useAuth() {
  const dispatch = useDispatch()
  const auth = useSelector(selectAuth)

  const signOut = useCallback(() => {
    if (useMockApi) sessionStorage.removeItem(STORAGE_KEY)
    dispatch(signedOut())
  }, [dispatch])

  const authenticate = useCallback(
    (credentials) => dispatch(signIn(credentials)).unwrap(),
    [dispatch],
  )

  return {
    ...auth,
    isAuthenticated: Boolean(auth.user),
    signIn: authenticate,
    signOut,
  }
}
