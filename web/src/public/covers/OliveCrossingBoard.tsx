// Renders the crossing terrain and voxel models through an elevated, behind-the-player camera.
import {
  CROSSING, railSignal,
  type CrossingDeathReason, type CrossingDirection, type CrossingVehicle, type CrossingWorld,
} from "./olive-crossing";
import { OLIVE_CHARACTERS, type OliveCharacter } from "./olive-crossing-characters";

export interface CrossingHopAnimation {
  direction: CrossingDirection;
  progress: number;
  offsetX: number;
  offsetY: number;
  blocked?: boolean;
}

export interface CrossingPlayerPose {
  dx: number;
  dy: number;
  jumpZ: number;
  bobZ: number;
  stretchZ: number;
  squashXY: number;
  leanX: number;
  leanY: number;
  stride: number;
  shadowScale: number;
}

export const HOP_DURATION_SECONDS = 0.14;

export function computePlayerPose(
  anim: CrossingHopAnimation | null | undefined,
  hop: number,
  riding: boolean,
  clockSeconds = 0,
): CrossingPlayerPose {
  if (!anim) {
    return {
      dx: 0,
      dy: 0,
      jumpZ: 0,
      bobZ: riding ? Math.sin(clockSeconds * 4.5) * 1.1 : 0,
      stretchZ: 1,
      squashXY: 1,
      leanX: 0,
      leanY: 0,
      stride: 0,
      shadowScale: 1,
    };
  }
  const t = Math.max(0, Math.min(1, anim.progress));
  const eased = 1 - (1 - t) * (1 - t);
  const remain = 1 - eased;
  const arc = Math.sin(t * Math.PI);
  const peakHeight = anim.blocked ? 7 : 20;
  const jumpZ = arc * peakHeight;

  let stretchZ = 1;
  let squashXY = 1;
  if (t < 0.2) {
    const k = t / 0.2;
    stretchZ = 1 - 0.12 * Math.sin(k * Math.PI);
    squashXY = 1 + 0.08 * Math.sin(k * Math.PI);
  } else if (t < 0.82) {
    const k = (t - 0.2) / 0.62;
    const mid = Math.sin(k * Math.PI);
    stretchZ = 1 + 0.14 * mid;
    squashXY = 1 - 0.06 * mid;
  } else {
    const k = (t - 0.82) / 0.18;
    const land = Math.sin(k * Math.PI);
    stretchZ = 1 - 0.1 * land;
    squashXY = 1 + 0.08 * land;
  }

  const leanAmt = arc * (anim.blocked ? 2.5 : 4.2);
  const leanX =
    anim.direction === "left" ? -leanAmt : anim.direction === "right" ? leanAmt : 0;
  const leanY =
    anim.direction === "up" ? -leanAmt : anim.direction === "down" ? leanAmt : 0;
  const strideSign = hop % 2 === 0 ? 1 : -1;
  const stride = arc * strideSign * (anim.blocked ? 1.5 : 4.5);

  return {
    dx: anim.offsetX * CELL * remain || 0,
    dy: anim.offsetY * CELL * remain || 0,
    jumpZ,
    bobZ: 0,
    stretchZ,
    squashXY,
    leanX,
    leanY,
    stride,
    shadowScale: Math.max(0.55, 1 - (jumpZ / 20) * 0.35),
  };
}

const CELL = CROSSING.cell;
const WORLD_WIDTH = CROSSING.columns * CELL;
const WORLD_HEIGHT = CROSSING.rows * CELL;
const FOCUS_Y = (CROSSING.rows - CROSSING.rearRows - 0.5) * CELL;
const DISTANCE = 1290;
const FOCAL_LENGTH = 1300;
const PITCH = 0.7;
const TILT = Math.sqrt(1 - PITCH ** 2);

export function crossingCamera(playerColumn: number, aspectRatio: number) {
  const project = (x: number, y: number, z = 0) => {
    const depth = DISTANCE - TILT * (y - FOCUS_Y) - PITCH * z;
    const scale = FOCAL_LENGTH / depth;
    return [(x - center) * scale, ((y - FOCUS_Y) * PITCH - z * TILT) * scale];
  };
  const groundY = (y: number) => FOCAL_LENGTH * (y - FOCUS_Y) * PITCH /
    (DISTANCE - TILT * (y - FOCUS_Y));
  const top = groundY(0);
  const height = groundY(WORLD_HEIGHT) - top;
  const width = height * aspectRatio;
  const playerX = (playerColumn + 0.5) * CELL;
  const margin = width * 0.16;
  const center = Math.max(playerX - margin, Math.min(WORLD_WIDTH / 2, playerX + margin));
  return {
    project,
    top,
    width,
    height,
    unproject(x: number, y: number) {
      const offset = y * DISTANCE / (FOCAL_LENGTH * PITCH + y * TILT);
      return {
        x: center + x * (DISTANCE - TILT * offset) / FOCAL_LENGTH,
        y: FOCUS_Y + offset,
      };
    },
  };
}

type Project = ReturnType<typeof crossingCamera>["project"];
type FaceColors = readonly [top: string, front: string, side: string];

function Ground({ project, x, y, width, depth, fill, z = 0 }: {
  project: Project; x: number; y: number; width: number; depth: number; fill: string; z?: number;
}) {
  return <polygon fill={fill} points={[
    project(x, y, z), project(x + width, y, z),
    project(x + width, y + depth, z), project(x, y + depth, z),
  ].join(" ")} />;
}

function Box({ project, x, y, z = 0, width, depth, height, colors }: {
  project: Project; x: number; y: number; z?: number;
  width: number; depth: number; height: number; colors: FaceColors;
}) {
  const a = project(x, y, z + height);
  const b = project(x + width, y, z + height);
  const c = project(x + width, y + depth, z + height);
  const d = project(x, y + depth, z + height);
  const [frontLeft, frontRight] = [project(x, y + depth, z), project(x + width, y + depth, z)];
  const side = (a[0] + b[0]) / 2 < 0
    ? [b, c, frontRight, project(x + width, y, z)]
    : [a, d, frontLeft, project(x, y, z)];
  return (
    <g>
      <polygon fill={colors[2]} points={side.join(" ")} />
      <polygon fill={colors[1]} points={[d, c, frontRight, frontLeft].join(" ")} />
      <polygon fill={colors[0]} points={[a, b, c, d].join(" ")} />
    </g>
  );
}

const BARK = ["#ac7f56", "#805431", "#5f3d25"] as const;
const LEAVES = ["#9ac667", "#6c9b46", "#537937"] as const;
const STONE = ["#b5b9a8", "#878d7e", "#696d63"] as const;
const WINDOW = ["#cde8ea", "#91c2cf", "#6b9ba9"] as const;
const TIRE = ["#454647", "#282a2b", "#181b1c"] as const;
const GOLD = ["#ffe47b", "#dda522", "#b07814"] as const;
const CAR: FaceColors[] = [
  ["#f57963", "#d24c41", "#a23a30"], ["#66b9e1", "#398bbb", "#246386"],
  ["#ffe282", "#d3a338", "#b38323"], ["#faf5da", "#c6c7b8", "#a5ab9d"],
];

function Tree({ project, x, y, rock }: { project: Project; x: number; y: number; rock: boolean }) {
  return rock ? (
    <Box project={project} x={x - 12} y={y - 10} width={24} depth={21} height={11} colors={STONE} />
  ) : (
    <>
      <Ground project={project} x={x - 15} y={y - 8} width={31} depth={27} fill="#829f48" />
      <Box project={project} x={x - 5} y={y - 5} width={10} depth={10} height={21} colors={BARK} />
      <Box project={project} x={x - 17} y={y - 15} z={15} width={34} depth={31} height={23} colors={LEAVES} />
      <Box project={project} x={x - 12} y={y - 11} z={38} width={24} depth={22} height={11} colors={LEAVES} />
    </>
  );
}

function Vehicle({ project, vehicle, direction, y }: {
  project: Project; vehicle: CrossingVehicle; direction: number; y: number;
}) {
  const x = vehicle.x * CELL + 3;
  const width = vehicle.width * CELL - 6;
  const train = vehicle.kind === "train";
  const tractor = vehicle.kind === "tractor";
  const truck = vehicle.kind === "truck" || vehicle.kind === "semi";
  const cars = train ? Math.max(2, Math.floor(vehicle.width)) : 1;
  const colors = train ? BARK : CAR[(vehicle.colorVariant ?? 0) % CAR.length];
  const cab = Math.min(width * 0.38, 24);
  const facingX = direction === 1 ? x + width - cab - 4 : x + 4;
  return (
    <g>
      {Array.from({ length: cars }, (_, i) => {
        const length = width / cars;
        const carX = x + length * i;
        return (
          <g key={i}>
            <Box project={project} x={carX + 4} y={y + 7} width={length - 10} depth={26} height={7} colors={TIRE} />
            <Box project={project} x={carX} y={y + 5} z={5} width={length - (train ? 3 : 0)} depth={30} height={train ? 25 : 12} colors={colors} />
            {train && <Box project={project} x={carX + 4} y={y + 34} z={18} width={length - 12} depth={2} height={7} colors={WINDOW} />}
          </g>
        );
      })}
      {!train && (
        <>
          {truck && <Box project={project} x={direction === 1 ? x + 2 : x + cab + 6} y={y + 6} z={15} width={width - cab - 8} depth={28} height={17} colors={BARK} />}
          <Box project={project} x={facingX} y={y + 9} z={17} width={cab} depth={22} height={tractor ? 19 : 11} colors={WINDOW} />
          <Box project={project} x={facingX - 1} y={y + 8} z={tractor ? 36 : 28} width={cab + 2} depth={24} height={3} colors={colors} />
        </>
      )}
    </g>
  );
}

const EYE_WHITE: FaceColors = ["#ffffff", "#f2f5f0", "#dfe5dd"] as const;

function Player({ project, x, y, character, facing, hop, hopAnim, clockSeconds, riding, over, deathReason }: {
  project: Project; x: number; y: number; character: OliveCharacter;
  facing: CrossingDirection; hop: number; hopAnim?: CrossingHopAnimation | null;
  clockSeconds: number; riding: boolean; over: boolean; deathReason?: CrossingDeathReason;
}) {
  const feet: FaceColors = [character.footFill, character.footFill, character.stroke];
  const body: FaceColors = [character.bodyTop, character.bodyFront, character.bodySide];
  const crest: FaceColors = [character.crestFill, character.crestFill, character.crestDark];
  const pose = computePlayerPose(over ? null : hopAnim, hop, riding && !over, clockSeconds);
  const rx = x + pose.dx;
  const ry = y + pose.dy;
  const baseZ = riding ? 8 : 0;
  const z = baseZ + pose.jumpZ + pose.bobZ;

  if (over && deathReason === "water") {
    const [sx, sy] = project(rx, ry, 1);
    return (
      <g className="crossing-player crossing-player-riding" data-world-x={x} data-world-y={y} data-facing={facing}>
        <g transform={`translate(${sx} ${sy})`} className="crossing-splash">
          <ellipse rx="20" ry="11" fill="none" stroke="#ffffff" strokeWidth="2.5" opacity="0.88" />
          <ellipse rx="13" ry="7" fill="none" stroke="#c8ecfa" strokeWidth="2" />
          <circle cx="-11" cy="-9" r="2.5" fill="#eaf8ff" />
          <circle cx="12" cy="-11" r="2.2" fill="#eaf8ff" />
          <circle cx="4" cy="-14" r="1.8" fill="#ffffff" />
        </g>
      </g>
    );
  }

  if (over && (deathReason === "traffic" || deathReason === "train")) {
    return (
      <g className="crossing-player crossing-player-riding" data-world-x={x} data-world-y={y} data-facing={facing}>
        <Box project={project} x={rx - 15} y={ry - 12} z={baseZ} width={30} depth={24} height={4} colors={body} />
        <Box project={project} x={rx - 6} y={ry - 5} z={baseZ + 4} width={12} depth={10} height={2} colors={crest} />
      </g>
    );
  }

  const sideways = facing === "left" || facing === "right";
  const bodyW = (sideways ? 19 : 22) * pose.squashXY;
  const bodyD = (sideways ? 22 : 19) * pose.squashXY;
  const bodyH = 23 * pose.stretchZ;
  const crestH = (character.id === "chef" ? 12 : 6) * pose.stretchZ;
  const crestShiftX = facing === "left" ? -2.2 : facing === "right" ? 2.2 : 0;
  const crestShiftY = facing === "up" ? -2.2 : facing === "down" ? 2.2 : 0;
  const bx = rx + pose.leanX * 0.45;
  const by = ry + pose.leanY * 0.45;
  const cx = rx + pose.leanX + crestShiftX;
  const cy = ry + pose.leanY + crestShiftY;
  const eyeZ = z + 5 + bodyH * 0.46;
  const shadowW = 24 * pose.shadowScale;
  const shadowD = 20 * pose.shadowScale;
  const footOffsetX = facing === "left" ? -9 : facing === "right" ? -6 : 0;
  const footOffsetY = facing === "up" ? -8 : -5;

  return (
    <g
      className={`crossing-player${riding ? " crossing-player-riding" : ""}`}
      data-world-x={x}
      data-world-y={y}
      data-facing={facing}
    >
      {!over && (
        <Ground
          project={project}
          x={rx - shadowW / 2}
          y={ry - shadowD / 2 + 2}
          z={baseZ + 0.15}
          width={shadowW}
          depth={shadowD}
          fill={`rgba(26,39,21,${(0.24 * pose.shadowScale).toFixed(2)})`}
        />
      )}
      {sideways ? (
        <>
          <Box
            project={project}
            x={rx + footOffsetX + pose.stride}
            y={ry - 9}
            z={z}
            width={15}
            depth={7}
            height={6}
            colors={feet}
          />
          <Box
            project={project}
            x={rx + footOffsetX - pose.stride}
            y={ry + 2}
            z={z}
            width={15}
            depth={7}
            height={6}
            colors={feet}
          />
        </>
      ) : (
        <>
          <Box
            project={project}
            x={rx - 10}
            y={ry + footOffsetY + pose.stride}
            z={z}
            width={8}
            depth={15}
            height={6}
            colors={feet}
          />
          <Box
            project={project}
            x={rx + 2}
            y={ry + footOffsetY - pose.stride}
            z={z}
            width={8}
            depth={15}
            height={6}
            colors={feet}
          />
        </>
      )}
      <Box
        project={project}
        x={bx - bodyW / 2}
        y={by - bodyD / 2}
        z={z + 5}
        width={bodyW}
        depth={bodyD}
        height={bodyH}
        colors={body}
      />
      <Box
        project={project}
        x={cx - 5}
        y={cy - 4.5}
        z={z + 5 + bodyH}
        width={10}
        depth={9}
        height={crestH}
        colors={crest}
      />
      {facing === "down" && [-6.8, 2.2].map((offset) => (
        <g key={offset}>
          <Box project={project} x={bx + offset} y={by + bodyD / 2 - 0.4} z={eyeZ} width={4.6} depth={1.6} height={5.2} colors={EYE_WHITE} />
          <Box project={project} x={bx + offset + 1.1} y={by + bodyD / 2 + 0.7} z={eyeZ + 0.9} width={2.4} depth={1.1} height={3.2} colors={TIRE} />
        </g>
      ))}
      {facing === "left" && (
        <g>
          <Box project={project} x={bx - bodyW / 2 - 1.5} y={by - 3.5} z={eyeZ} width={1.8} depth={5} height={5} colors={EYE_WHITE} />
          <Box project={project} x={bx - bodyW / 2 - 2.2} y={by - 2.3} z={eyeZ + 0.9} width={1.1} depth={2.6} height={3} colors={TIRE} />
          <Box project={project} x={bx - bodyW / 2 + 1.2} y={by + bodyD / 2 - 0.5} z={eyeZ} width={4.4} depth={1.5} height={4.8} colors={EYE_WHITE} />
          <Box project={project} x={bx - bodyW / 2 + 1.4} y={by + bodyD / 2 + 0.6} z={eyeZ + 0.9} width={2.2} depth={1.1} height={3} colors={TIRE} />
        </g>
      )}
      {facing === "right" && (
        <g>
          <Box project={project} x={bx + bodyW / 2 - 0.3} y={by - 3.5} z={eyeZ} width={1.8} depth={5} height={5} colors={EYE_WHITE} />
          <Box project={project} x={bx + bodyW / 2 + 1.1} y={by - 2.3} z={eyeZ + 0.9} width={1.1} depth={2.6} height={3} colors={TIRE} />
          <Box project={project} x={bx + bodyW / 2 - 5.6} y={by + bodyD / 2 - 0.5} z={eyeZ} width={4.4} depth={1.5} height={4.8} colors={EYE_WHITE} />
          <Box project={project} x={bx + bodyW / 2 - 3.6} y={by + bodyD / 2 + 0.6} z={eyeZ + 0.9} width={2.2} depth={1.1} height={3} colors={TIRE} />
        </g>
      )}
    </g>
  );
}

export default function CrossingBoard({
  aspectRatio, hop, hopAnim = null, clockSeconds = 0, world, character = OLIVE_CHARACTERS[0], facing = "up", eagleProgress = 0,
}: {
  aspectRatio: number; hop: number; hopAnim?: CrossingHopAnimation | null; clockSeconds?: number;
  world: CrossingWorld; character?: OliveCharacter;
  facing?: CrossingDirection; eagleProgress?: number;
}) {
  const camera = crossingCamera(world.playerColumn, aspectRatio);
  const { project, top, width, height } = camera;
  const playerX = (world.playerColumn + 0.5) * CELL;
  const playerY = (world.playerRow + 0.5) * CELL;
  const [px, py] = project(playerX, playerY);
  const edge = 1800;
  const lanes = world.lanes;
  return (
    <svg viewBox={`${-width / 2} ${top} ${width} ${height}`} aria-hidden="true">
      <rect x={-width / 2} y={top} width={width} height={height} fill="#a8c66f" />
      <g className="crossing-ground">
        {lanes.map((lane, row) => {
          const y = row * CELL;
          const grass = lane.type === "grass";
          const water = lane.type === "water";
          const rail = lane.type === "rail";
          const fill = grass ? (lane.id % 2 ? "#a9cd69" : "#b7d778")
            : water ? "#52a4cf" : rail ? "#8e7861" : "#525962";
          return (
            <g key={lane.id} className="crossing-lane">
              <Ground project={project} x={-edge} y={y} width={WORLD_WIDTH + edge * 2} depth={CELL + 0.2} fill={fill} />
              {grass && Array.from({ length: CROSSING.columns + 12 }, (_, i) => (
                (i + lane.id) % 2 === 0 && <Ground key={i} project={project} x={(i - 6) * CELL} y={y} width={CELL}
                  depth={CELL} fill="rgba(255,255,230,.08)" />
              ))}
              {row > 0 && <Ground project={project} x={-edge} y={y} width={WORLD_WIDTH + edge * 2} depth={3}
                fill={grass ? "#829f49" : water ? "#398ab7" : rail ? "#77634e" : "#414951"} />}
              {lane.type === "road" && Array.from({ length: 100 }, (_, i) => (
                <Ground key={i} project={project} x={(i - 39) * 40} y={y + 24} width={19} depth={2.3} fill="#dedac9" />
              ))}
              {rail && (
                <>
                  {Array.from({ length: 76 }, (_, i) => <Ground key={i} project={project} x={(i - 25) * 24} y={y + 5} width={5} depth={40} fill="#65513c" />)}
                  {[12, 36].map((offset) => <Ground key={offset} project={project} x={-edge} y={y + offset} width={WORLD_WIDTH + edge * 2} depth={3} fill="#d0d2c7" />)}
                </>
              )}
              {!water && [0, WORLD_WIDTH].map((x) => <Ground key={x} project={project} x={x - 1.5} y={y} width={3} depth={CELL} fill="#253b2533" />)}
            </g>
          );
        })}
      </g>
      {lanes.map((lane, row) => {
        const y = row * CELL;
        const signal = lane.type === "rail" ? railSignal(lane) : null;
        const riding = lane.type === "water" && lane.waterStyle === "logs";
        const obstacles = lane.type === "grass" ? [-3, -1, ...(lane.obstacles ?? []), CROSSING.columns + 1, CROSSING.columns + 3] : [];
        return (
          <g key={lane.id}>
            {obstacles.map((col) => <Tree key={col} project={project} x={(col + 0.5) * CELL} y={y + 25}
              rock={col >= 0 && col < CROSSING.columns && (col * 3 + lane.id) % 5 === 0} />)}
            {lane.type === "water" && (riding ? lane.logs?.map((log) =>
              <Box key={log.id} project={project} x={log.x * CELL} y={y + 13} width={log.width * CELL} depth={24} height={8} colors={BARK} />
            ) : lane.pads.map((col) =>
              <Box key={col} project={project} x={col * CELL + 8} y={y + 12} width={34} depth={28} height={4} colors={LEAVES} />
            ))}
            {(lane.coins ?? []).map((col) =>
              <Box key={col} project={project} x={col * CELL + 18} y={y + 19} width={14} depth={13} height={8} colors={GOLD} />
            )}
            {lane.vehicles.map((vehicle) => <Vehicle key={vehicle.id} project={project} vehicle={vehicle} y={y + 7} direction={lane.direction} />)}
            {signal && (
              <Box project={project} x={Math.max(-22, playerX - width * 0.44)} y={y + 3}
                width={8} depth={8} height={29}
                colors={signal === "green" ? LEAVES : signal === "yellow" ? GOLD : CAR[0]} />
            )}
            {row === world.playerRow && <Player project={project} x={playerX} y={playerY} character={character}
              facing={facing} hop={hop} hopAnim={hopAnim} clockSeconds={clockSeconds}
              riding={riding} over={world.over} deathReason={world.deathReason} />}
          </g>
        );
      })}
      {eagleProgress > 0.6 && (
        <path transform={`translate(${px} ${py - (1 - eagleProgress) * 125})`}
          opacity={Math.min(0.9, (eagleProgress - 0.5) * 1.9)}
          d="M-30-13L-8-5L0-15L8-5L30-13L10 10H-10Z" fill="#59412d" />
      )}
    </svg>
  );
}
