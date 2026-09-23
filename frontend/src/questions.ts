export type Answers = {
  situation: string
  mood: string
  category: string
  hunger: string
  taste: string
}

export type Question = {
  key: keyof Answers
  title: string
  options: { value: string; label: string }[]
}

export const QUESTIONS: Question[] = [
  {
    key: 'situation',
    title: '누구와 먹나요?',
    options: [
      { value: 'ALONE', label: '혼밥' },
      { value: 'FRIENDS', label: '친구' },
      { value: 'DATE', label: '연인' },
      { value: 'GROUP', label: '가족·회식' },
    ],
  },
  {
    key: 'mood',
    title: '지금 기분은 어때요?',
    options: [
      { value: 'EXCITED', label: '신남' },
      { value: 'NORMAL', label: '평범' },
      { value: 'DOWN', label: '우울·지침' },
      { value: 'STRESSED', label: '스트레스' },
    ],
  },
  {
    key: 'category',
    title: '어떤 종류가 당기나요?',
    options: [
      { value: 'KOREAN', label: '한식' },
      { value: 'CHINESE', label: '중식' },
      { value: 'JAPANESE', label: '일식' },
      { value: 'WESTERN', label: '양식' },
      { value: 'SNACK', label: '분식' },
      { value: 'ANY', label: '상관없음' },
    ],
  },
  {
    key: 'hunger',
    title: '얼마나 배고파요?',
    options: [
      { value: 'LIGHT', label: '살짝 출출' },
      { value: 'MODERATE', label: '적당히' },
      { value: 'STARVING', label: '매우 배고픔' },
    ],
  },
  {
    key: 'taste',
    title: '어떤 맛이 좋아요?',
    options: [
      { value: 'SPICY', label: '매콤' },
      { value: 'MILD', label: '담백' },
      { value: 'RICH', label: '기름진' },
      { value: 'SWEET_SOUR', label: '달달·새콤' },
    ],
  },
]
