// the questionnaire's options; ANY is only an answer, so no food shows its label
export const CATEGORIES = [
  { value: 'KOREAN', label: '한식' },
  { value: 'CHINESE', label: '중식' },
  { value: 'JAPANESE', label: '일식' },
  { value: 'WESTERN', label: '양식' },
  { value: 'SNACK', label: '분식' },
  { value: 'SOUTHEAST_ASIAN', label: '동남아' },
  { value: 'MEXICAN', label: '멕시칸' },
  { value: 'INDIAN_MIDDLE_EASTERN', label: '인도·중동' },
  { value: 'MEAT', label: '고기·구이' },
  { value: 'FAST_FOOD', label: '패스트푸드·치킨' },
  { value: 'BRUNCH', label: '브런치' },
  { value: 'ANY', label: '상관없음' },
]

export function categoryLabel(value: string | undefined) {
  return CATEGORIES.find((category) => category.value === value)?.label
}
