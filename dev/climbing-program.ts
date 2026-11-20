// The "Climb or hangboard" workout: the Intermediate (6C–7C) Repeater plan for
// the Beastmaker 2000 from the Beastmaker × Beacon training manual by Ned
// Feehally, plus a pinch block.
// https://www.beaconclimbing.com/wp-content/uploads/Beastmaker-Beacon-Training-Manual.pdf
//
// The manual runs one set of each hang as a circuit (2–3 min between hangs)
// and repeats the circuit 2–3 times with 10 min between rounds. Here each
// hang is done as 2 straight sets with 2½ min between them instead, since the
// runner goes step by step. Hold numbers are the manual's; the apps draw
// the board from shared/boards.json.

/** A hang: 6 × 7 s on / 3 s off per set, added weight confirmed before starting. */
const hang = { reps: 6, work_seconds: 7, rest_seconds: 3, sets: 2, set_rest_seconds: 150, weight: "added", weight_prompt: "start" };

/** Both hands on hold n (left nL, right nR) of the Beastmaker 2000 (shared/boards.json). */
const holds = (n: number) => ({ board: "bm2000", left_hold: `${n}L`, right_hold: `${n}R` });

const LOCK = "Vary the lock each hang: arms at 140°, 90°, full lock, then again.";

export interface ProgramStep {
  title: string;
  config: Record<string, unknown>;
  description: string;
}

export const CLIMBING_PROGRAM: ProgramStep[] = [
  { title: "Front 2", config: { ...hang, ...holds(8) },
    description: `Index + middle fingers, open. ${LOCK}` },
  // The manual says hold 10 (small edges); the large edges suit for now.
  { title: "4-finger open", config: { ...hang, ...holds(5) },
    description: `Open hand: index and pinkie straight, no thumb. ${LOCK}` },
  { title: "Middle 2", config: { ...hang, ...holds(8) },
    description: `Middle + ring fingers, open. ${LOCK}` },
  { title: "Slopers", config: { ...hang, ...holds(1) },
    description: `4 fingers, no thumbs on the holds below. ${LOCK}` },
  { title: "3-finger open", config: { ...hang, ...holds(5) },
    description: `Index + middle + ring, open hand. ${LOCK}` },
  { title: "Half crimp", config: { ...hang, ...holds(10) },
    description: `4 fingers half crimped, thumb off. ${LOCK}` },
  {
    title: "Pinch block",
    config: {
      reps: 6, work_seconds: 7, rest_seconds: 3, sets: 3, sides: 2, side_rest_seconds: 30, set_rest_seconds: 90,
      weight: "load", weight_prompt: "start",
    },
    description: "One hand at a time, left then right at the same weight: lift and hold 7 s, set it down 3 s.",
  },
];
