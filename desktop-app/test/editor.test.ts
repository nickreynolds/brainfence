// The task editor keeps what it doesn't manage: other completion alternatives,
// and step config keys such as a timed_sets step's sets and rest periods.
import './helpers'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildCompletionAlternatives, emptyAlternativeLocation, emptyEditorState, newEditableStep, stepConfigJson } from '../src/shared/taskEditor'

const gym = { verification_type: 'gps', label: 'Gym', extra: 1, verification_config: { lat: 1, lng: 2, radius_m: 50, mode: 'enter', min_duration_m: 30, note: 'x' } }
const other = { verification_type: 'duration', verification_config: { duration_seconds: 60 } }

test('alternatives: managed entry rewritten in place, others kept', () => {
  const s = {
    ...emptyEditorState(), taskType: 'workout', originalAlternatives: [other, gym],
    alternativeLocation: { enabled: true, label: 'Climbing gym', latitude: '40.7', longitude: '-73.9', radiusMeters: 150, minDurationM: 30 },
  }
  assert.deepEqual(buildCompletionAlternatives(s), [other, {
    verification_type: 'gps', label: 'Climbing gym', extra: 1,
    verification_config: { note: 'x', latitude: 40.7, longitude: -73.9, radius_meters: 150, mode: 'enter', min_duration_m: 30 },
  }])
})

test('alternatives: turning it off removes only the managed entry', () => {
  const s = { ...emptyEditorState(), taskType: 'workout', originalAlternatives: [other, gym], alternativeLocation: emptyAlternativeLocation() }
  assert.deepEqual(buildCompletionAlternatives(s), [other])
})

test('timed_sets edits keep sets, rests and weight logging', () => {
  const step = {
    ...newEditableStep(), stepType: 'timed_sets', defaultSets: 6, durationSeconds: 7, originalStepType: 'timed_sets',
    originalConfig: { reps: 5, work_seconds: 10, rest_seconds: 3, sets: 3, set_rest_seconds: 180, log_weight: true },
  }
  assert.deepEqual(stepConfigJson(step), { reps: 6, work_seconds: 7, rest_seconds: 3, sets: 3, set_rest_seconds: 180, log_weight: true })
  assert.deepEqual(stepConfigJson({ ...newEditableStep(), stepType: 'timed_sets', defaultSets: 2, durationSeconds: 10 }), { rest_seconds: 20, reps: 2, work_seconds: 10 })
})
