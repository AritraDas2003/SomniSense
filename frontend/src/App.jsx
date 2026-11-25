import { useEffect } from 'react'
import { BrowserRouter } from 'react-router-dom'
import { Provider, useDispatch } from 'react-redux'
import { store } from './app/store'
import { restoreSession } from './features/auth/authSlice'
import SessionExpiryListener from './app/SessionExpiryListener'
import AppRouter from './app/router'
import './App.css'

function AuthBootstrap() {
  const dispatch = useDispatch()

  useEffect(() => {
    dispatch(restoreSession())
  }, [dispatch])

  return null
}

function App() {
  return (
    <Provider store={store}>
      <BrowserRouter>
        <AuthBootstrap />
        <SessionExpiryListener />
        <AppRouter />
      </BrowserRouter>
    </Provider>
  )
}

export default App
