import { useEffect, useState } from 'react'
import { ErrorText, Field, Logo, TextInput } from '../components/ui'
import { useAction } from '../lib/hooks'

/** Port of SignInScreen / SignUpScreen. */
export function AuthScreen() {
  const [mode, setMode] = useState<'signIn' | 'signUp'>('signIn')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const { busy, error, run, setError } = useAction()

  // Email confirmation links land here (shared/CONTRACTS.md, Email links).
  useEffect(() => {
    const show = (n: string) => {
      setNotice(n)
      setMode('signIn')
    }
    void window.api.auth.takeNotice().then((n) => n && show(n))
    return window.api.auth.onNotice(show)
  }, [])

  const submit = () => {
    setNotice(null)
    if (!email.trim() || !password) return setError('Email and password are required')
    if (mode === 'signUp') {
      if (password.length < 6) return setError('Password must be at least 6 characters')
      if (password !== confirm) return setError('Passwords do not match')
      void run(async () => {
        const { needsConfirmation } = await window.api.auth.signUp(email, password)
        if (needsConfirmation) {
          setNotice('Check your email to confirm your account, then sign in.')
          setMode('signIn')
        }
      })
    } else {
      void run(() => window.api.auth.signIn(email, password))
    }
  }

  return (
    <div className="center-screen" style={{ minHeight: '100vh', padding: 24 }}>
      <div className="stack" style={{ width: 'min(380px, 100%)', textAlign: 'left' }}>
        <div className="row" style={{ gap: 12, marginBottom: 8 }}>
          <div className="nav-brand" style={{ padding: 0 }}><Logo /></div>
          <h1 style={{ fontSize: 26 }}>Brainfence</h1>
        </div>
        <div className="title-md">{mode === 'signIn' ? 'Sign in' : 'Create account'}</div>
        <Field label="Email">
          <TextInput value={email} onChange={setEmail} type="email" autoFocus onEnter={submit} />
        </Field>
        <Field label="Password">
          <TextInput value={password} onChange={setPassword} type="password" onEnter={submit} />
        </Field>
        {mode === 'signUp' && (
          <Field label="Confirm password">
            <TextInput value={confirm} onChange={setConfirm} type="password" onEnter={submit} />
          </Field>
        )}
        <ErrorText error={error} />
        {notice && <div className="small primary-text">{notice}</div>}
        <button className="btn block" onClick={submit} disabled={busy}>
          {busy ? 'Please wait…' : mode === 'signIn' ? 'Sign in' : 'Create account'}
        </button>
        <button className="btn text" onClick={() => { setMode(mode === 'signIn' ? 'signUp' : 'signIn'); setError(null) }}>
          {mode === 'signIn' ? "Don't have an account? Sign up" : 'Already have an account? Sign in'}
        </button>
      </div>
    </div>
  )
}
