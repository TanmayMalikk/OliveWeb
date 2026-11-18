// Cover users: renders Crossy Road-style 2.5D voxel lanes, traffic, logs, coins, and Olive characters.
import { memo } from "react";
import {
  CROSSING,
  railSignal,
  type CrossingDirection,
  type CrossingLane,
  type CrossingVehicle,
  type CrossingWorld,
} from "./olive-crossing";
import {
  OLIVE_CHARACTERS,
  VoxelOlive,
  type OliveCharacter,
} from "./olive-crossing-characters";

const laneY = (row: number) => row * CROSSING.cell;
const worldWidth = CROSSING.columns * CROSSING.cell;
const BLEED_COLS = 14;
const BLEED_X = -BLEED_COLS * CROSSING.cell;
const BLEED_WIDTH = worldWidth + BLEED_COLS * 2 * CROSSING.cell;

const CAR_PALETTES = [
  { body: "#d94f43", top: "#ef6d61", dark: "#9e3128" },
  { body: "#2f8fc4", top: "#53b0e3", dark: "#1d6087" },
  { body: "#e2aa32", top: "#f5c856", dark: "#a87616" },
  { body: "#eae7da", top: "#ffffff", dark: "#b6b2a2" },
];

const TRUCK_PALETTES = [
  { box: "#c7743e", top: "#df905b", cab: "#e8eae3" },
  { box: "#4e8159", top: "#699f74", cab: "#f2efe4" },
  { box: "#53799b", top: "#7097ba", cab: "#eceae0" },
  { box: "#9a5c53", top: "#b87970", cab: "#f0eee5" },
];

function VoxelObstacle({
  col,
  laneId,
  y,
}: {
  col: number;
  laneId: number;
  y: number;
}) {
  const cx = col * CROSSING.cell + 25;
  const isBoulder = (laneId + col * 3) % 5 === 0;

  if (isBoulder) {
    return (
      <g transform={`translate(${cx} ${y + 26})`}>
        <ellipse cx="1" cy="12" rx="16" ry="7" fill="rgba(28, 42, 22, 0.24)" />
        <polygon
          points="-14,10 -16,-3 -6,-13 9,-13 16,-2 14,10"
          fill="#8c9489"
          stroke="#464d44"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
        <polygon
          points="-13,-3 -6,-13 9,-13 4,-3 -8,-1"
          fill="#adb5aa"
        />
        <polygon points="4,-3 16,-2 14,10 2,10" fill="#6d756b" />
        <ellipse cx="-6" cy="-6" rx="4" ry="2.2" fill="#779d4b" opacity="0.85" />
      </g>
    );
  }

  const heightStep = ((laneId + col) % 3) * 3;
  return (
    <g className="crossing-tree" transform={`translate(${cx} ${y + 24})`}>
      <ellipse cx="1" cy="13" rx="16" ry="7" fill="rgba(26, 42, 20, 0.25)" />
      {/* Voxel Trunk */}
      <rect
        x="-5"
        y="-2"
        width="10"
        height="15"
        rx="1.5"
        fill="#755233"
        stroke="#422c19"
        strokeWidth="1.3"
      />
      {/* Bottom Voxel Canopy Cube */}
      <rect
        x="-16"
        y={-15 - heightStep * 0.4}
        width="32"
        height="17"
        rx="3"
        fill="#538534"
        stroke="#2f521b"
        strokeWidth="1.4"
      />
      {/* Mid Sunlit Leaf Tier */}
      <rect
        x="-13"
        y={-22 - heightStep * 0.7}
        width="26"
        height="12"
        rx="2.5"
        fill="#6ca148"
        stroke="#365c20"
        strokeWidth="1.2"
      />
      {/* Top Crown Highlight */}
      <rect
        x="-9"
        y={-27 - heightStep}
        width="18"
        height="9"
        rx="2"
        fill="#88be5c"
      />
      {/* Tiny decorative olives on alternate trees */}
      {(laneId + col) % 2 === 0 && (
        <>
          <circle cx="-7" cy={-8 - heightStep * 0.4} r="2.1" fill="#c9de6d" />
          <circle cx="8" cy={-14 - heightStep * 0.6} r="2.1" fill="#d9e77c" />
        </>
      )}
    </g>
  );
}

function VoxelVehicle({
  vehicle,
  direction,
  y,
}: {
  vehicle: CrossingVehicle;
  direction: 1 | -1;
  y: number;
}) {
  const pxWidth = vehicle.width * CROSSING.cell;
  const insetPx = CROSSING.vehicleInset * CROSSING.cell;
  const bodyW = pxWidth - insetPx * 2;
  const flipTransform =
    direction === -1 ? `translate(${pxWidth} 0) scale(-1 1)` : undefined;

  if (vehicle.kind === "train") {
    const carCount = Math.max(2, Math.floor(vehicle.width));
    return (
      <g transform={`translate(${vehicle.x * CROSSING.cell} ${y + 6})`}>
        <g transform={flipTransform}>
          <g className="crossing-vehicle-body crossing-train">
            <rect
              x={insetPx}
              y="32"
              width={bodyW}
              height="6"
              rx="2"
              fill="rgba(22, 26, 22, 0.35)"
            />
            {/* Main Locomotive & Coaches */}
            <rect
              x={insetPx}
              y="-2"
              width={bodyW}
              height="35"
              rx="5"
              fill="#6b4439"
              stroke="#2f1e18"
              strokeWidth="1.8"
            />
            <rect
              x={insetPx + 3}
              y="-4"
              width={bodyW - 6}
              height="9"
              rx="3"
              fill="#8b5a4b"
            />
            {/* Cream & Amber Express Stripe */}
            <rect
              x={insetPx + 2}
              y="21"
              width={bodyW - 4}
              height="6"
              fill="#dfb257"
            />
            {/* Passenger Windows */}
            {Array.from({ length: carCount }, (_, idx) => (
              <rect
                key={idx}
                x={insetPx + 10 + idx * 42}
                y="7"
                width="24"
                height="11"
                rx="2"
                fill="#d9ecde"
                stroke="#3b2821"
                strokeWidth="1.2"
              />
            ))}
            {/* Front Cab Windshield & Bright Golden Headlamp */}
            <rect
              x={insetPx + bodyW - 18}
              y="6"
              width="13"
              height="12"
              rx="2"
              fill="#bce4f7"
            />
            <circle cx={insetPx + bodyW - 3} cy="25" r="4" fill="#fff09a" />
            <polygon
              points={`${insetPx + bodyW},20 ${insetPx + bodyW + 24},13 ${insetPx + bodyW + 24},33 ${insetPx + bodyW},29`}
              fill="#fff09a"
              opacity="0.22"
            />
          </g>
        </g>
      </g>
    );
  }

  if (vehicle.kind === "car") {
    const palette = CAR_PALETTES[vehicle.colorVariant ?? 0];
    return (
      <g transform={`translate(${vehicle.x * CROSSING.cell} ${y + 9})`}>
        <g transform={flipTransform}>
          <ellipse
            className="crossing-dust"
            cx="-2"
            cy="26"
            rx="6"
            ry="2.5"
            fill="#d9d2aa"
          />
          <g className="crossing-vehicle-body">
            <rect x={insetPx + 2} y="27" width={bodyW - 4} height="5" rx="2" fill="rgba(16, 22, 18, 0.32)" />
            {/* Lower Chassis */}
            <rect
              x={insetPx}
              y="10"
              width={bodyW}
              height="18"
              rx="4"
              fill={palette.body}
              stroke={palette.dark}
              strokeWidth="1.5"
            />
            {/* Sunlit Hood */}
            <rect x={insetPx + 2} y="9" width={bodyW - 4} height="5" rx="2" fill={palette.top} />
            {/* Voxel Roof Cabin */}
            <rect
              x={insetPx + 7}
              y="1"
              width={bodyW - 17}
              height="12"
              rx="3"
              fill={palette.top}
              stroke={palette.dark}
              strokeWidth="1.3"
            />
            {/* Tinted Windshield */}
            <rect x={insetPx + bodyW - 19} y="3" width="9" height="8" rx="1.5" fill="#bfe7f5" />
            {/* Headlight & Taillight */}
            <rect x={insetPx + bodyW - 3} y="14" width="3" height="6" rx="1" fill="#fff29b" />
            <rect x={insetPx} y="14" width="3" height="5" rx="1" fill="#f04a3e" />
            {/* Wheels */}
            <circle className="crossing-wheel" cx={insetPx + 9} cy="28" r="4.5" fill="#282c2a" />
            <circle className="crossing-wheel" cx={insetPx + bodyW - 9} cy="28" r="4.5" fill="#282c2a" />
          </g>
        </g>
      </g>
    );
  }

  if (vehicle.kind === "semi") {
    const palette = TRUCK_PALETTES[vehicle.colorVariant ?? 1];
    return (
      <g transform={`translate(${vehicle.x * CROSSING.cell} ${y + 7})`}>
        <g transform={flipTransform}>
          <ellipse
            className="crossing-dust"
            cx="-3"
            cy="29"
            rx="8"
            ry="3"
            fill="#d9d2aa"
          />
          <g className="crossing-vehicle-body">
            <rect x={insetPx + 2} y="29" width={bodyW - 4} height="5" rx="2" fill="rgba(16, 22, 18, 0.34)" />
            {/* Long Grove Cargo Trailer */}
            <rect
              x={insetPx}
              y="2"
              width={bodyW - 22}
              height="27"
              rx="3"
              fill={palette.box}
              stroke="#323832"
              strokeWidth="1.5"
            />
            <rect x={insetPx + 2} y="2" width={bodyW - 26} height="7" rx="2" fill={palette.top} />
            <line x1={insetPx + 14} y1="12" x2={insetPx + bodyW - 28} y2="12" stroke="rgba(255,255,255,0.28)" strokeWidth="2" />
            {/* Hitch */}
            <rect x={insetPx + bodyW - 24} y="20" width="6" height="5" fill="#3a403d" />
            {/* Cab */}
            <rect
              x={insetPx + bodyW - 19}
              y="6"
              width="19"
              height="23"
              rx="3"
              fill={palette.cab}
              stroke="#3d433e"
              strokeWidth="1.5"
            />
            <rect x={insetPx + bodyW - 10} y="9" width="8" height="9" rx="1.5" fill="#bde6f7" />
            <rect x={insetPx + bodyW - 3} y="20" width="3" height="5" rx="1" fill="#fff29b" />
            <rect x={insetPx} y="20" width="3" height="5" rx="1" fill="#e8493c" />
            {/* Multi-Axle Wheels */}
            <circle className="crossing-wheel" cx={insetPx + 10} cy="30" r="4.5" fill="#272a28" />
            <circle className="crossing-wheel" cx={insetPx + 22} cy="30" r="4.5" fill="#272a28" />
            <circle className="crossing-wheel" cx={insetPx + bodyW - 9} cy="30" r="4.5" fill="#272a28" />
          </g>
        </g>
      </g>
    );
  }

  if (vehicle.kind === "truck") {
    const palette = TRUCK_PALETTES[vehicle.colorVariant ?? 0];
    return (
      <g transform={`translate(${vehicle.x * CROSSING.cell} ${y + 8})`}>
        <g transform={flipTransform}>
          <ellipse
            className="crossing-dust"
            cx="-3"
            cy="29"
            rx="7"
            ry="3"
            fill="#d9d2aa"
          />
          <g className="crossing-vehicle-body">
            <rect x={insetPx + 2} y="28" width={bodyW - 4} height="5" rx="2" fill="rgba(16, 22, 18, 0.32)" />
            {/* Truck Bed / Box */}
            <rect
              x={insetPx}
              y="3"
              width={bodyW - 15}
              height="25"
              rx="3.5"
              fill={palette.box}
              stroke="#3a3d33"
              strokeWidth="1.5"
            />
            <rect x={insetPx + 2} y="3" width={bodyW - 19} height="6" rx="2" fill={palette.top} />
            {/* Cab */}
            <rect
              x={insetPx + bodyW - 17}
              y="8"
              width="17"
              height="20"
              rx="3"
              fill={palette.cab}
              stroke="#3d433e"
              strokeWidth="1.5"
            />
            <rect x={insetPx + bodyW - 9} y="10" width="7" height="7" rx="1.5" fill="#bfe7f5" />
            <rect x={insetPx + bodyW - 3} y="20" width="3" height="4.5" fill="#fff29b" />
            <rect x={insetPx} y="20" width="3" height="4.5" fill="#e8493c" />
            <circle className="crossing-wheel" cx={insetPx + 10} cy="29" r="4.5" fill="#282c2a" />
            <circle className="crossing-wheel" cx={insetPx + bodyW - 8} cy="29" r="4.5" fill="#282c2a" />
          </g>
        </g>
      </g>
    );
  }

  // Orchard Tractor
  return (
    <g transform={`translate(${vehicle.x * CROSSING.cell} ${y + 8})`}>
      <g transform={flipTransform}>
        <ellipse
          className="crossing-dust"
          cx="-3"
          cy="29"
          rx="7"
          ry="3"
          fill="#d9d2aa"
        />
        <g className="crossing-vehicle-body">
          <rect x={insetPx + 2} y="28" width={bodyW - 4} height="5" rx="2" fill="rgba(16, 22, 18, 0.32)" />
          {/* Tractor Engine Hood & Cab */}
          <rect
            x={insetPx}
            y="3"
            width={bodyW - 12}
            height="23"
            rx="3"
            fill="#5c7e3b"
            stroke="#2f441d"
            strokeWidth="1.5"
          />
          <rect
            x={insetPx + 8}
            y="10"
            width={bodyW - 8}
            height="17"
            rx="3"
            fill="#709747"
            stroke="#2f441d"
            strokeWidth="1.4"
          />
          <rect x={insetPx + 4} y="6" width="11" height="8" rx="1.5" fill="#cce9e0" />
          {/* Exhaust pipe & Front Lamp */}
          <rect x={insetPx + bodyW - 9} y="3" width="3.5" height="9" rx="1" fill="#464d47" />
          <rect x={insetPx + bodyW - 3} y="16" width="3" height="4.5" fill="#fff29b" />
          {/* Signature Big Rear Tractor Tire & Smaller Front Tire */}
          <circle className="crossing-wheel" cx={insetPx + 10} cy="27" r="6" fill="#272b26" />
          <circle cx={insetPx + 10} cy="27" r="2.2" fill="#d9c66a" />
          <circle className="crossing-wheel" cx={insetPx + bodyW - 7} cy="29" r="4" fill="#272b26" />
        </g>
      </g>
    </g>
  );
}

const StaticLaneDecoration = memo(function StaticLaneDecoration({
  lane,
  row,
  y,
}: {
  lane: CrossingLane;
  row: number;
  y: number;
}) {
  if (lane.type === "grass") {
    return (
      <>
        <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height={CROSSING.cell} fill={row % 2 ? "#add36a" : "#a1c85f"} />
        {/* Crossy Road Voxel Checkerboard Tiles spanning full stage width */}
        {Array.from({ length: CROSSING.columns + BLEED_COLS * 2 }, (_, idx) => {
          const col = idx - BLEED_COLS;
          return (col + lane.id) % 2 === 0 ? (
            <rect
              key={col}
              x={col * CROSSING.cell}
              y={y}
              width={CROSSING.cell}
              height={CROSSING.cell - 4}
              fill="rgba(255, 255, 255, 0.08)"
            />
          ) : null;
        })}
        {/* Subtle Crossy Road forest-edge shade outside the playable corridor */}
        <rect x={BLEED_X} y={y} width={-BLEED_X} height={CROSSING.cell} fill="rgba(26, 48, 18, 0.14)" />
        <rect x={worldWidth} y={y} width={-BLEED_X} height={CROSSING.cell} fill="rgba(26, 48, 18, 0.14)" />
        {/* Elevated 3D Front Turf Lip */}
        <rect x={BLEED_X} y={y + CROSSING.cell - 5} width={BLEED_WIDTH} height="5" fill="#779e41" />
        {/* Tiny Wildflower Specks outside Obstacle cells */}
        {Array.from({ length: 5 }, (_, idx) => {
          const col = (lane.id * 5 + idx * 4) % CROSSING.columns;
          if (lane.obstacles?.includes(col)) return null;
          return (
            <g key={idx} transform={`translate(${col * CROSSING.cell + 14 + (idx % 3) * 9} ${y + 18 + (idx % 2) * 12})`}>
              <circle r="2.2" fill={idx % 2 === 0 ? "#ffffff" : "#f6db6b"} opacity="0.85" />
            </g>
          );
        })}
        {/* Out-of-bounds Edge Treeline framing full-width screen */}
        {[-6, -4, -2, -1, CROSSING.columns, CROSSING.columns + 1, CROSSING.columns + 3, CROSSING.columns + 5].map(
          (col) =>
            (col + lane.id) % 2 === 0 ? (
              <VoxelObstacle key={`edge-${col}`} col={col} laneId={lane.id} y={y} />
            ) : null,
        )}
        {/* Playable Obstacle Trees & Boulders */}
        {(lane.obstacles ?? []).map((col) => (
          <VoxelObstacle key={col} col={col} laneId={lane.id} y={y} />
        ))}
      </>
    );
  }

  if (lane.type === "road") {
    return (
      <>
        <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height={CROSSING.cell} fill="#4b525b" />
        {/* Sunken Top Curb & Edge Markings */}
        <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height="4" fill="#363c43" />
        <rect x={BLEED_X} y={y + 46} width={BLEED_WIDTH} height="4" fill="#5e6670" />
        <path
          d={`M${BLEED_X} ${y + 25}H${BLEED_X + BLEED_WIDTH}`}
          stroke="#e5e8df"
          strokeWidth="2.5"
          strokeDasharray="16 14"
          opacity="0.72"
        />
      </>
    );
  }

  if (lane.type === "rail") {
    return (
      <>
        <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height={CROSSING.cell} fill="#62554a" />
        <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height="4" fill="#463b32" />
        {Array.from({ length: (CROSSING.columns + BLEED_COLS * 2) * 2 }, (_, idx) => {
          const tie = idx - BLEED_COLS * 2;
          return (
            <rect
              key={tie}
              x={tie * 25 + 4}
              y={y + 6}
              width="6"
              height="38"
              rx="1"
              fill="#433429"
            />
          );
        })}
        <path
          d={`M${BLEED_X} ${y + 13}H${BLEED_X + BLEED_WIDTH}M${BLEED_X} ${y + 37}H${BLEED_X + BLEED_WIDTH}`}
          stroke="#ced6db"
          strokeWidth="4"
        />
        <path
          d={`M${BLEED_X} ${y + 15}H${BLEED_X + BLEED_WIDTH}M${BLEED_X} ${y + 39}H${BLEED_X + BLEED_WIDTH}`}
          stroke="#8a969e"
          strokeWidth="1.5"
        />
      </>
    );
  }

  // Water Lane (Recessed Cerulean River + Lily Pads if lilypad style)
  return (
    <>
      <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height={CROSSING.cell} fill="#44a3da" />
      <rect x={BLEED_X} y={y} width={BLEED_WIDTH} height="5" fill="#2c75a6" />
      {[15, 34].map((offset) => (
        <path
          key={offset}
          className="crossing-wave"
          d={`M${BLEED_X} ${y + offset}H${BLEED_X + BLEED_WIDTH}`}
          stroke="#bce7fb"
          strokeWidth="2"
          strokeDasharray="18 24"
          opacity="0.58"
        />
      ))}
      {lane.waterStyle !== "logs" &&
        lane.pads.map((column) => (
          <g
            key={column}
            className="crossing-lilypad"
            transform={`translate(${column * CROSSING.cell + 25} ${y + 25})`}
          >
            <g className="crossing-lilypad-inner">
              <ellipse cx="0" cy="4" rx="18" ry="12" fill="#3f6e33" />
              <ellipse
                rx="18"
                ry="12"
                fill="#67ac4f"
                stroke="#457837"
                strokeWidth="2"
              />
              <path d="M0 0L18-7V7Z" fill="#44a3da" />
              {column % 4 === 0 && (
                <g transform="translate(-4 -4)">
                  <circle r="4.5" fill="#f7b7c6" />
                  <circle r="2" fill="#fff09d" />
                </g>
              )}
            </g>
          </g>
        ))}
    </>
  );
});

export default function CrossingBoard({
  camera,
  hop,
  world,
  character = OLIVE_CHARACTERS[0],
  facing = "up",
  eagleProgress = 0,
}: {
  camera: { x: number; width: number };
  hop: number;
  world: CrossingWorld;
  character?: OliveCharacter;
  facing?: CrossingDirection;
  eagleProgress?: number;
}) {
  const playerX = world.playerColumn * CROSSING.cell + 25;
  const playerY = world.playerRow * CROSSING.cell + 25;
  const activeLane = world.lanes[world.playerRow];
  const ridingLog =
    activeLane?.type === "water" && activeLane.waterStyle === "logs";

  return (
    <svg
      viewBox={`${camera.x} 0 ${camera.width} ${CROSSING.rows * CROSSING.cell}`}
      aria-hidden="true"
    >
      {/* Full-bleed meadow backdrop prevents any vertical letterbox gap */}
      <rect
        x={BLEED_X}
        y={-400}
        width={BLEED_WIDTH}
        height={CROSSING.rows * CROSSING.cell + 800}
        fill="#a1c85f"
      />
      {world.lanes.map((lane, row) => {
        const y = laneY(row);
        const signal = lane.type === "rail" ? railSignal(lane) : null;
        return (
          <g key={lane.id} className="crossing-lane">
            <StaticLaneDecoration lane={lane} row={row} y={y} />

            {/* Drifting Wooden Crossy Logs */}
            {lane.type === "water" &&
              lane.waterStyle === "logs" &&
              (lane.logs ?? []).map((log) => {
                const logW = log.width * CROSSING.cell;
                return (
                  <g
                    key={log.id}
                    transform={`translate(${log.x * CROSSING.cell} ${y + 10})`}
                  >
                    {/* Floating Water Foam Shadow */}
                    <rect
                      x="2"
                      y="27"
                      width={logW - 4}
                      height="6"
                      rx="3"
                      fill="#2b75a3"
                    />
                    {/* Log Cylinder Body */}
                    <rect
                      x="0"
                      y="4"
                      width={logW}
                      height="24"
                      rx="5"
                      fill="#7f5431"
                      stroke="#4c2f19"
                      strokeWidth="1.6"
                    />
                    {/* Sunlit Bark Top */}
                    <rect
                      x="3"
                      y="5"
                      width={logW - 6}
                      height="8"
                      rx="3"
                      fill="#a17146"
                    />
                    {/* Cut Wood Grain End Cap */}
                    <ellipse
                      cx={lane.direction === 1 ? logW - 6 : 6}
                      cy="16"
                      rx="4.5"
                      ry="9"
                      fill="#dec38e"
                      stroke="#6d4727"
                      strokeWidth="1.2"
                    />
                    {/* Bark Knots */}
                    <rect x={logW * 0.35} y="15" width="14" height="3" rx="1" fill="#633f22" />
                    <rect x={logW * 0.62} y="19" width="10" height="3" rx="1" fill="#633f22" />
                  </g>
                );
              })}

            {/* Collectible Golden Olive Coins */}
            {(lane.coins ?? []).map((col) => (
              <g
                key={col}
                transform={`translate(${col * CROSSING.cell + 25} ${y + 24})`}
              >
                <ellipse cx="0" cy="12" rx="8" ry="3.5" fill="rgba(22, 34, 18, 0.25)" />
                <circle r="10.5" fill="#c99112" stroke="#6c4b04" strokeWidth="1.4" />
                <circle cx="-1" cy="-1" r="8.5" fill="#f7cf46" />
                <ellipse cx="-1" cy="-1" rx="3.2" ry="4.5" fill="#d49e19" />
                <circle cx="-3.5" cy="-4.5" r="2" fill="#fff6b8" />
              </g>
            ))}

            {/* Crossy Road Flashing X Crossing Signal */}
            {lane.type === "rail" && (
              <g
                className={`crossing-rail-signal crossing-rail-signal-${signal}`}
                transform={`translate(${camera.x + 8} ${y + 3})`}
              >
                <rect
                  width="18"
                  height="44"
                  rx="6"
                  fill="#2d322c"
                  stroke="#ebe6ce"
                  strokeWidth="2"
                />
                {(
                  [
                    ["red", 9, "#e65245"],
                    ["yellow", 22, "#e4b947"],
                    ["green", 35, "#5fa45e"],
                  ] as const
                ).map(([colour, cy, fill]) => (
                  <circle
                    key={colour}
                    className={`crossing-signal-light crossing-signal-light-${colour}`}
                    cx="9"
                    cy={cy}
                    r="5"
                    fill={fill}
                  />
                ))}
              </g>
            )}

            {/* Moving Traffic & Trains */}
            {(lane.type === "road" || lane.type === "rail") &&
              lane.vehicles.map((vehicle) => (
                <VoxelVehicle
                  key={vehicle.id}
                  vehicle={vehicle}
                  direction={lane.direction}
                  y={y}
                />
              ))}
          </g>
        );
      })}

      {/* Orchard Hawk Warning Shadow if lingering too long */}
      {eagleProgress > 0.6 && (
        <g
          transform={`translate(${playerX} ${playerY - (1 - eagleProgress) * 140})`}
          opacity={Math.min(0.85, (eagleProgress - 0.55) * 1.9)}
        >
          <ellipse cx="0" cy="24" rx="28" ry="12" fill="rgba(16, 22, 16, 0.35)" />
          <polygon points="-32,-18 -8,-8 0,-18 8,-8 32,-18 12,4 -12,4" fill="#4f3d2f" stroke="#281d15" strokeWidth="1.5" />
          <polygon points="-4,4 4,4 0,11" fill="#e09f3e" />
        </g>
      )}

      {/* Playable 2.5D Voxel Olive Character */}
      <g
        className={`crossing-player${ridingLog ? " crossing-player-riding" : ""}`}
        style={{ transform: `translate(${playerX}px, ${playerY}px)` }}
      >
        {!world.over && (
          <ellipse cx="0" cy="14" rx="14" ry="6" fill="rgba(22, 34, 18, 0.28)" />
        )}
        <g key={hop} className="crossing-player-hop">
          <VoxelOlive
            character={character}
            facing={facing}
            crashed={world.over && world.deathReason !== "water"}
            splashed={world.over && world.deathReason === "water"}
          />
        </g>
      </g>
    </svg>
  );
}
