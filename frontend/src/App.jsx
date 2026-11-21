import { BrowserRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import { store } from './app/store'
import SessionExpiryListener from './app/SessionExpiryListener'
import AppRouter from './app/router'
import './App.css'

function App() {
  return (
    <Provider store={store}>
      <BrowserRouter>
        <SessionExpiryListener />
        <AppRouter />
      </BrowserRouter>
    </Provider>
  )
}

export default App
