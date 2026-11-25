import { useCallback } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import { selectAuth, signIn, signOutUser } from '../features/auth/authSlice'

export function useAuth() {
  const dispatch = useDispatch()
  const auth = useSelector(selectAuth)

  const signOut = useCallback(
    () => dispatch(signOutUser()).unwrap(),
    [dispatch],
  )

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
