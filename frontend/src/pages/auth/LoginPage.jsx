import { useState } from 'react'
import { Activity, ArrowRight, Eye, EyeOff, MoonStar, ShieldCheck } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../hooks/useAuth'
import { useMockApi } from '../../services/api/apiConfig'

export default function LoginPage() {
  const { signIn, sessionExpired, error: authError } = useAuth()
  const mockMode = useMockApi
  const navigate = useNavigate()
  const location = useLocation()
  const [passwordVisible, setPasswordVisible] = useState(false)
  const {
    register,
    handleSubmit: handleFormSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ defaultValues: { username: '', password: '' } })

  async function handleSubmit(credentials) {
    try {
      await signIn(credentials)
      navigate(location.state?.from?.pathname || '/dashboard', { replace: true })
    } catch (loginError) {
      setError('root.server', {
        type: 'server',
        message: loginError || 'Unable to sign in. Please try again.',
      })
    }
  }

  return (
    <main className="login-screen">
      <section className="login-story">
        <div className="login-brand">
          <div className="brand-mark"><Activity size={20} strokeWidth={2.5} /></div>
          <span>somni<span className="brand-accent">sense</span></span>
        </div>
        <div className="login-story-content">
          <span className="eyebrow"><span className="eyebrow-line" /> SLEEP MONITORING, MADE CLEAR</span>
          <h1>Better nights<br />start with <em>better insight.</em></h1>
          <p>A calm, connected view of overnight monitoring for care teams and the people they support.</p>
          <div className="login-visual">
            <div className="visual-orbit orbit-outer" />
            <div className="visual-orbit orbit-inner" />
            <div className="visual-moon"><MoonStar size={30} strokeWidth={1.4} /></div>
            <div className="visual-signal signal-one" />
            <div className="visual-signal signal-two" />
            <div className="visual-signal signal-three" />
            <span className="visual-caption">OVERNIGHT<br />MONITORING</span>
            <div className="visual-reading"><span>SpO₂</span><strong>—<small>%</small></strong><i>Awaiting signal</i></div>
          </div>
        </div>
        <div className="login-story-footer"><ShieldCheck size={15} /> Staff access only <span>·</span> Demo environment</div>
      </section>

      <section className="login-form-panel">
        <div className="login-form-wrap">
          <div className="login-mobile-brand">
            <div className="brand-mark"><Activity size={20} strokeWidth={2.5} /></div>
            <span>somni<span className="brand-accent">sense</span></span>
          </div>
          <div className="login-heading">
            <span className="eyebrow">WELCOME BACK</span>
            <h2>Sign in to your workspace</h2>
            <p>Use your staff account to continue.</p>
          </div>

          {sessionExpired && <div className="session-expired-notice" role="status">Your session expired. Sign in again to continue.</div>}
          {mockMode && (
            <div className="mock-auth-notice">
              <span className="notice-dot" />
              <div><strong>Mock authentication enabled</strong><small>Demo accounts are for local UI testing only.</small></div>
            </div>
          )}

          <form className="login-form" onSubmit={handleFormSubmit(handleSubmit)} noValidate>
            <label className="field-label" htmlFor="login-email">Work email</label>
            <input
              id="login-email"
              className="form-input"
              type="email"
              autoComplete="username"
              placeholder="you@organization.com"
              aria-invalid={Boolean(errors.username)}
              aria-describedby={errors.username ? 'login-email-error' : undefined}
              {...register('username', {
                required: 'Enter your work email.',
                pattern: { value: /^\S+@\S+\.\S+$/, message: 'Enter a valid email address.' },
              })}
            />
            {errors.username && <span id="login-email-error" className="field-error" role="alert">{errors.username.message}</span>}
            <div className="field-label-row">
              <label className="field-label" htmlFor="login-password">Password</label>
              <span className="field-hint">Demo account</span>
            </div>
            <div className="password-input-wrap">
              <input
                id="login-password"
                className="form-input"
                type={passwordVisible ? 'text' : 'password'}
                autoComplete="current-password"
                placeholder="Enter your password"
                aria-invalid={Boolean(errors.password)}
                aria-describedby={errors.password ? 'login-password-error' : undefined}
                {...register('password', { required: 'Enter your password.' })}
              />
              <button
                className="password-toggle"
                type="button"
                aria-label={passwordVisible ? 'Hide password' : 'Show password'}
                onClick={() => setPasswordVisible((visible) => !visible)}
              >
                {passwordVisible ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            </div>
            {errors.password && <span id="login-password-error" className="field-error" role="alert">{errors.password.message}</span>}
            {(errors.root?.server?.message || authError) && <div className="form-error" role="alert">{errors.root?.server?.message || authError}</div>}
            <button className="button button-primary login-submit" type="submit" disabled={isSubmitting}>
              {isSubmitting ? <><span className="spinner" /> Signing in...</> : <>Sign in <ArrowRight size={17} /></>}
            </button>
          </form>

          {mockMode && (
            <div className="demo-credentials">
              <strong>LOCAL DEMO CREDENTIALS</strong>
              <p><span>Admin</span> admin@somnisense.local <code>SomniDemo123!</code></p>
              <p><span>Operator</span> operator@somnisense.local <code>SomniDemo123!</code></p>
            </div>
          )}
          <div className="login-disclaimer">SomniSense is an educational monitoring prototype, not a medical diagnostic product.</div>
        </div>
      </section>
    </main>
  )
}
