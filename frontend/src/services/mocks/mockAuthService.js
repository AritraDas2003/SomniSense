const mockUsers = [
  {
    id: 'mock-admin-001',
    name: 'Morgan Lee',
    username: 'admin',
    email: 'admin@somnisense.local',
    role: 'ADMIN',
    active: true,
    isMock: true,
  },
  {
    id: 'mock-operator-001',
    name: 'Taylor Kim',
    username: 'operator',
    email: 'operator@somnisense.local',
    role: 'OPERATOR',
    active: true,
    isMock: true,
  },
]

const demoPassword = 'SomniDemo123!'

export async function login({ login: loginIdentifier, username, password }) {
  await new Promise((resolve) => window.setTimeout(resolve, 450))
  const normalized = (loginIdentifier ?? username ?? '').trim().toLowerCase()
  const user = mockUsers.find(
    (candidate) =>
      candidate.username.toLowerCase() === normalized
      || candidate.email.toLowerCase() === normalized,
  )

  if (!user || password !== demoPassword) {
    const error = new Error('Invalid username/email or password')
    error.status = 401
    error.code = 'INVALID_CREDENTIALS'
    throw error
  }

  return user
}
