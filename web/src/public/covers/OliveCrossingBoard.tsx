// Renders the crossing terrain and voxel models through an elevated, behind-the-player camera.
import {
  CROSSING, railSignal,
  type CrossingDirection, type CrossingVehicle, type CrossingWorld,
} from "./olive-crossing";
import { OLIVE_CHARACTERS, type OliveCharacter } from "./olive-crossing-characters";

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

function Player({ project, x, y, character, facing, hop, riding, over }: {
  project: Project; x: number; y: number; character: OliveCharacter;
  facing: CrossingDirection; hop: number; riding: boolean; over: boolean;
}) {
  const [px, py] = project(x, y);
  const feet: FaceColors = [character.footFill, character.footFill, character.stroke];
  const body: FaceColors = [character.bodyTop, character.bodyFront, character.bodySide];
  const crest: FaceColors = [character.crestFill, character.crestFill, character.crestDark];
  const z = riding ? 8 : 0;
  return (
    <g className="crossing-player" data-world-x={x} data-world-y={y}
      style={{ transform: `translate(${px}px, ${py}px)` }}>
      <g transform={`translate(${-px} ${-py})`}>
        {!over && <Ground project={project} x={x - 11} y={y - 7} width={24} depth={20} fill="rgba(26,39,21,.23)" />}
        <g key={hop} className="crossing-player-hop">
          <Box project={project} x={x - 10} y={y - 6} z={z} width={8} depth={16} height={6} colors={feet} />
          <Box project={project} x={x + 2} y={y - 6} z={z} width={8} depth={16} height={6} colors={feet} />
          <Box project={project} x={x - 11} y={y - 9} z={z + 5} width={22} depth={19} height={23} colors={body} />
          <Box project={project} x={x - 5} y={y - 4} z={z + 28} width={10} depth={9} height={character.id === "chef" ? 12 : 6} colors={crest} />
          {facing === "down" && [-6, 4].map((offset) => (
            <Box key={offset} project={project} x={x + offset} y={y + 10} z={z + 16} width={3} depth={1} height={4} colors={TIRE} />
          ))}
        </g>
      </g>
    </g>
  );
}

export default function CrossingBoard({
  aspectRatio, hop, world, character = OLIVE_CHARACTERS[0], facing = "up", eagleProgress = 0,
}: {
  aspectRatio: number; hop: number; world: CrossingWorld; character?: OliveCharacter;
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
              facing={facing} hop={hop} riding={riding} over={world.over} />}
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
