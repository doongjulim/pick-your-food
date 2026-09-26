import { request } from '../../shared/api.ts'
import type { Food } from '../../shared/api.ts'
import type { Answers } from './questions.ts'

export type Recommendation = {
  best: Food
  alternatives: Food[]
}

export function fetchRecommendation(answers: Answers): Promise<Recommendation> {
  return request('/api/recommendations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(answers),
  })
}
