const mockUsers = [
  {
    id: 'mock-admin-001',
    name: 'Morgan Lee',
    username: 'admin@somnisense.local',
    role: 'ADMIN',
    isMock: true,
  },
  {
    id: 'mock-operator-001',
    name: 'Taylor Kim',
    username: 'operator@somnisense.local',
    role: 'OPERATOR',
    isMock: true,
  },
]

const demoPassword = 'SomniDemo123!'

export async function login({ username, password }) {
  await new Promise((resolve) => window.setTimeout(resolve, 450))
  const user = mockUsers.find((candidate) => candidate.username === username.trim().toLowerCase())

  if (!user || password !== demoPassword) {
    const error = new Error('The email or password is incorrect.')
    error.status = 401
    throw error
  }

  return user
}
