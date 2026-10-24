// Port of data/contemplation/ContemplationLibrary.kt. Adding a starter deck
// copies it into the user's tables tagged with its key; the library is a
// template, never read at delivery time.

import type { ContemplationResponseType, DeckOrdering } from './models'

export interface LibraryItem {
  title: string | null
  body: string
  attribution: string | null
}

export interface LibraryDeck {
  key: string
  name: string
  description: string
  ordering: DeckOrdering
  responseType: ContemplationResponseType
  responseLabel: string | null
  weight: number
  items: LibraryItem[]
}

const REMEMBRANCES_SOURCE = 'Upajjhatthana Sutta (AN 5.57), tr. Thich Nhat Hanh'

export const CONTEMPLATION_LIBRARY: LibraryDeck[] = [
  {
    key: 'five_remembrances',
    name: 'The Five Remembrances',
    description: "The Buddha's five daily recollections, delivered in turn.",
    ordering: 'sequential',
    responseType: 'affirm',
    responseLabel: 'I remember',
    weight: 4,
    items: [
      { title: 'The First Remembrance', body: 'I am of the nature to grow old. There is no way to escape growing old.', attribution: REMEMBRANCES_SOURCE },
      { title: 'The Second Remembrance', body: 'I am of the nature to have ill health. There is no way to escape having ill health.', attribution: REMEMBRANCES_SOURCE },
      { title: 'The Third Remembrance', body: 'I am of the nature to die. There is no way to escape death.', attribution: REMEMBRANCES_SOURCE },
      {
        title: 'The Fourth Remembrance',
        body: 'All that is dear to me and everyone I love are of the nature to change. There is no way to escape being separated from them.',
        attribution: REMEMBRANCES_SOURCE,
      },
      {
        title: 'The Fifth Remembrance',
        body: 'My actions are my only true belongings. I cannot escape the consequences of my actions. My actions are the ground upon which I stand.',
        attribution: REMEMBRANCES_SOURCE,
      },
    ],
  },
  {
    key: 'sayings',
    name: 'Sayings',
    description: 'Occasional sayings on death, comparison and contentment.',
    ordering: 'random',
    responseType: 'none',
    responseLabel: null,
    weight: 1,
    items: [
      { title: null, body: 'To be a happy person, one must contemplate death five times daily.', attribution: 'Bhutanese saying' },
      { title: null, body: 'Comparison is the thief of joy.', attribution: 'Attributed to Theodore Roosevelt' },
      { title: null, body: 'There is no such thing as bad weather, only bad clothing.', attribution: 'Scandinavian saying' },
      {
        title: null,
        body: 'You could leave life right now. Let that determine what you do and say and think.',
        attribution: 'Marcus Aurelius, Meditations 2.11',
      },
      { title: null, body: 'Enough is as good as a feast.', attribution: 'English proverb' },
    ],
  },
]

export const DEFAULT_AFFIRM_LABEL = 'I remember'
