// A hangboard (shared/boards.json) with a step's holds highlighted and marked
// L / R. With onPick, tapping a hold picks it (the step editor).
// shared/CONTRACTS.md (Board holds).

import type { BoardHold, BoardLayout } from '@shared/boards.generated'

// The board is a picture of wood, so it keeps its own colors in both themes.
const INK = '#1B1B21'
const LIME = '#D7FF1F'
const WOOD = '#E8CDA2'
const WOOD_EDGE = '#C9A877'
const WOOD_TOP = '#F1DDBB'
const SLOPER_EDGE = '#D9BC8E'
const HOLE = '#B4875A'

export function BoardView({ board, leftHold, rightHold, onPick, maxWidth }: {
  board: BoardLayout
  leftHold: string | null
  rightHold: string | null
  onPick?: (holdId: string) => void
  maxWidth?: number
}) {
  const { body } = board
  const marker = (h: BoardHold, label: string, key: string) => {
    const cx = h.x + h.w / 2
    // Above the hold: over the board for slopers, in the gap above a row otherwise.
    const cy = h.kind === 'sloper' ? body.y - 30 : h.y - 34
    const wide = label.length > 1
    return (
      <g key={key} pointerEvents="none">
        <rect x={cx - (wide ? 46 : 30)} y={cy - 30} width={wide ? 92 : 60} height={60} rx={30} fill={INK} />
        <text x={cx} y={cy + 12} fontFamily="system-ui, sans-serif" fontSize={36} fontWeight={700} fill={LIME} textAnchor="middle">{label}</text>
      </g>
    )
  }
  const left = board.holds.find((h) => h.id === leftHold)
  const right = board.holds.find((h) => h.id === rightHold)
  const markers = left && right && left.id === right.id
    ? [marker(left, 'LR', 'lr')]
    : [left && marker(left, 'L', 'l'), right && marker(right, 'R', 'r')]
  // Slopers first: the top pockets sit on the raised centre block.
  const ordered = [...board.holds.filter((h) => h.kind === 'sloper'), ...board.holds.filter((h) => h.kind !== 'sloper')]
  return (
    <svg
      viewBox={`0 0 ${board.width} ${board.height}`}
      role="img"
      aria-label={board.name}
      style={{ display: 'block', width: '100%', maxWidth, height: 'auto' }}
    >
      <rect x={body.x} y={body.y} width={body.w} height={body.h} rx={body.r} fill={WOOD} stroke={WOOD_EDGE} strokeWidth={4} />
      <line x1={body.x + 10} y1={board.dividerY} x2={body.x + body.w - 10} y2={board.dividerY} stroke="#D2B285" strokeWidth={4} />
      {ordered.map((h) => {
        const on = h.id === leftHold || h.id === rightHold
        const sloper = h.kind === 'sloper'
        return (
          <rect
            key={h.id}
            x={h.x} y={h.y} width={h.w} height={h.h} rx={h.r}
            fill={on ? LIME : sloper ? WOOD_TOP : HOLE}
            stroke={on ? INK : sloper ? SLOPER_EDGE : 'none'}
            strokeWidth={on ? 8 : sloper ? 3 : 0}
            style={onPick ? { cursor: 'pointer' } : undefined}
            onClick={onPick ? () => onPick(h.id) : undefined}
          >
            <title>{`Hold ${h.number} · ${h.name}`}</title>
          </rect>
        )
      })}
      {markers}
    </svg>
  )
}
