// The "Climb or hangboard" workout: the Intermediate (6C–7C) Repeater plan for
// the Beastmaker 2000 from the Beastmaker × Beacon training manual by Ned
// Feehally, plus a pinch block.
// https://www.beaconclimbing.com/wp-content/uploads/Beastmaker-Beacon-Training-Manual.pdf
//
// The manual runs one set of each hang as a circuit (2–3 min between hangs)
// and repeats the circuit 2–3 times with 10 min between rounds. Here each
// hang is done as 2 straight sets with 2½ min between them instead, since the
// runner goes step by step. Hold numbers are the manual's.

/** A hang: 6 × 7 s on / 3 s off per set, added weight confirmed before starting. */
const hang = { reps: 6, work_seconds: 7, rest_seconds: 3, sets: 2, set_rest_seconds: 150, weight: "added", weight_prompt: "start" };

const LOCK = "Vary the lock each hang: arms at 140°, 90°, full lock, then again.";

export interface ProgramStep {
  title: string;
  config: Record<string, unknown>;
  description: string;
  /** Image in dev/routine-images/png, without .png. */
  image?: string;
}

export const CLIMBING_PROGRAM: ProgramStep[] = [
  { title: "Front 2 — hold 8", config: hang, image: "bm2000-front2-hold8",
    description: `Deep 2-finger pockets, index + middle, open. ${LOCK}` },
  { title: "4-finger open — hold 10", config: hang, image: "bm2000-open4-hold10",
    description: `Small edges, open hand: index and pinkie straight, no thumb. ${LOCK}` },
  { title: "Middle 2 — hold 8", config: hang, image: "bm2000-middle2-hold8",
    description: `Deep 2-finger pockets, middle + ring, open. ${LOCK}` },
  { title: "Slopers — hold 1", config: hang, image: "bm2000-sloper-hold1",
    description: `45° slopers, 4 fingers, no thumbs underneath. ${LOCK}` },
  { title: "3-finger open — hold 5", config: hang, image: "bm2000-open3-hold5",
    description: `Large flat edges, index + middle + ring, open hand. ${LOCK}` },
  { title: "Half crimp — hold 10", config: hang, image: "bm2000-crimp-hold10",
    description: `Small edges, 4 fingers half crimped, thumb off. ${LOCK}` },
  {
    title: "Pinch block",
    config: {
      reps: 6, work_seconds: 7, rest_seconds: 3, sets: 3, sides: 2, side_rest_seconds: 30, set_rest_seconds: 90,
      weight: "load", weight_prompt: "each_set",
    },
    description: "One hand at a time, left then right at the same weight: lift and hold 7 s, set it down 3 s.",
  },
];

export const ROUTINE_IMAGES_BUCKET = "routine-images";
/** Bump when redrawing images, so clients' caches fetch the new ones. */
export const IMAGE_VERSION = "v1";
