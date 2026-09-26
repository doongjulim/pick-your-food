import { request } from '../../shared/api.ts'
import type { Food } from '../../shared/api.ts'

export function fetchRandom(): Promise<Food> {
  return request('/api/foods/random')
}
