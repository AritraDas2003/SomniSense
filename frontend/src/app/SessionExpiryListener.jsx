import { useEffect } from 'react'
import { useDispatch } from 'react-redux'
import { sessionExpired, STORAGE_KEY } from '../features/auth/authSlice'
import { useMockApi } from '../services/api/apiConfig'

export default function SessionExpiryListener() {
  const dispatch = useDispatch()

  useEffect(() => {
    function handleSessionExpired(event) {
      if (useMockApi) {
        sessionStorage.removeItem(STORAGE_KEY)
      }
      dispatch(sessionExpired(event?.detail || null))
    }

    window.addEventListener('somnisense:session-expired', handleSessionExpired)
    return () => window.removeEventListener('somnisense:session-expired', handleSessionExpired)
  }, [dispatch])

  return null
}
