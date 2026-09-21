import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import '../i18n'
import App from '../App'

describe('App', () => {
  it('redirects an unauthenticated visitor to the login page', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })
})
