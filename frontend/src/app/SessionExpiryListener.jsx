import { useEffect } from 'react'
import { useDispatch } from 'react-redux'
import { sessionExpired, STORAGE_KEY } from '../features/auth/authSlice'

export default function SessionExpiryListener() {
  const dispatch = useDispatch()

  useEffect(() => {
    function handleSessionExpired() {
      sessionStorage.removeItem(STORAGE_KEY)
      dispatch(sessionExpired())
    }

    window.addEventListener('somnisense:session-expired', handleSessionExpired)
    return () => window.removeEventListener('somnisense:session-expired', handleSessionExpired)
  }, [dispatch])

  return null
}
