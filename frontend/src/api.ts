import type { Answers } from './questions.ts'

export type Food = {
  id: string
  name: string
  description: string
}

export type Recommendation = {
  best: Food
  alternatives: Food[]
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json() as Promise<T>
}

export function fetchRandom(): Promise<Food> {
  return request('/api/foods/random')
}

export function fetchRecommendation(answers: Answers): Promise<Recommendation> {
  return request('/api/recommendations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(answers),
  })
}
