// Cover users: draws roads, rails, water, and the moving olive.
import { CROSSING, railSignal, type CrossingWorld } from "./olive-crossing";

const laneY = (row: number) => row * CROSSING.cell;

export default function CrossingBoard({
  camera,
  hop,
  world,
}: {
  camera: { x: number; width: number };
  hop: number;
  world: CrossingWorld;
}) {
  return (
          <svg
            viewBox={`${camera.x} 0 ${camera.width} ${CROSSING.rows * CROSSING.cell}`}
            aria-hidden="true"
          >
            {world.lanes.map((lane, row) => {
              const y = laneY(row);
              const signal = lane.type === "rail" ? railSignal(lane) : null;
              return (
                <g key={lane.id} className="crossing-lane">
                  <rect
                    x="0"
                    y={y}
                    width={CROSSING.columns * CROSSING.cell}
                    height="50"
                    fill={
                      lane.type === "road"
                        ? "#8b887c"
                        : lane.type === "rail"
                          ? "#867a69"
                          : lane.type === "water"
                            ? "#78aaa8"
                            : row % 2
                              ? "#b8cb8d"
                              : "#a9c17e"
                    }
                  />
                  {lane.type === "road" || lane.type === "rail" ? (
                    <>
                      {lane.type === "road" ? (
                        <path
                          d={`M0 ${y + 25}H${CROSSING.columns * CROSSING.cell}`}
                          stroke="#d9d2aa"
                          strokeWidth="2"
                          strokeDasharray="14 12"
                          opacity=".7"
                        />
                      ) : (
                        <>
                          {Array.from(
                            { length: CROSSING.columns * 2 },
                            (_, tie) => (
                              <rect
                                key={tie}
                                x={tie * 25 + 4}
                                y={y + 5}
                                width="5"
                                height="40"
                                rx="1"
                                fill="#574c3e"
                                opacity=".75"
                              />
                            ),
                          )}
                          <path
                            d={`M0 ${y + 13}H${CROSSING.columns * CROSSING.cell}M0 ${y + 37}H${CROSSING.columns * CROSSING.cell}`}
                            stroke="#d1c5aa"
                            strokeWidth="4"
                          />
                          <g
                            className={`crossing-rail-signal crossing-rail-signal-${signal}`}
                            transform={`translate(${camera.x + 9} ${y + 3})`}
                          >
                            <rect
                              width="18"
                              height="44"
                              rx="6"
                              fill="#30352f"
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
                        </>
                      )}
                      {lane.vehicles.map((vehicle) => (
                        <g
                          key={vehicle.id}
                          transform={`translate(${vehicle.x * CROSSING.cell} ${y + 8})`}
                        >
                          <ellipse
                            className="crossing-dust"
                            cx={
                              lane.direction === 1
                                ? -3
                                : vehicle.width * CROSSING.cell + 3
                            }
                            cy="29"
                            rx="8"
                            ry="3"
                            fill="#d9d2aa"
                          />
                          {vehicle.kind === "train" ? (
                            <g className="crossing-vehicle-body crossing-train">
                              <rect
                                x={CROSSING.vehicleInset * CROSSING.cell}
                                y="-2"
                                width={
                                  (vehicle.width - CROSSING.vehicleInset * 2) *
                                  CROSSING.cell
                                }
                                height="36"
                                rx="5"
                                fill="#704f42"
                                stroke="#3e3931"
                                strokeWidth="2"
                              />
                              {Array.from(
                                {
                                  length: Math.max(
                                    2,
                                    Math.floor(vehicle.width),
                                  ),
                                },
                                (_, window) => (
                                  <rect
                                    key={window}
                                    x={18 + window * 36}
                                    y="6"
                                    width="19"
                                    height="11"
                                    rx="2"
                                    fill="#dbe4d2"
                                  />
                                ),
                              )}
                              <path
                                d={`M8 25H${vehicle.width * CROSSING.cell - 8}`}
                                stroke="#c89d59"
                                strokeWidth="4"
                              />
                            </g>
                          ) : (
                            <g className="crossing-vehicle-body">
                              <rect
                                x={CROSSING.vehicleInset * CROSSING.cell}
                                width={
                                  (vehicle.width - CROSSING.vehicleInset * 2) *
                                  CROSSING.cell
                                }
                                height="31"
                                rx="7"
                                fill={
                                  vehicle.kind === "truck"
                                    ? "#c78b4d"
                                    : "#61744a"
                                }
                                stroke="#3e4934"
                                strokeWidth="2"
                              />
                              <rect
                                x="8"
                                y="5"
                                width="14"
                                height="9"
                                rx="2"
                                fill="#dce7d2"
                              />
                              <circle
                                className="crossing-wheel"
                                cx="10"
                                cy="32"
                                r="5"
                                fill="#35382f"
                              />
                              <circle
                                className="crossing-wheel"
                                cx={vehicle.width * CROSSING.cell - 10}
                                cy="32"
                                r="5"
                                fill="#35382f"
                              />
                              {vehicle.kind === "truck" && (
                                <ellipse
                                  cx={vehicle.width * CROSSING.cell - 17}
                                  cy="15"
                                  rx="7"
                                  ry="10"
                                  fill="#52643a"
                                />
                              )}
                            </g>
                          )}
                        </g>
                      ))}
                    </>
                  ) : lane.type === "water" ? (
                    <>
                      {[14, 34].map((offset) => (
                        <path
                          key={offset}
                          className="crossing-wave"
                          d={`M0 ${y + offset}H${CROSSING.columns * CROSSING.cell}`}
                          stroke="#c9e2d8"
                          strokeWidth="2"
                          strokeDasharray="18 22"
                          opacity=".55"
                        />
                      ))}
                      {lane.pads.map((column) => (
                        <g
                          key={column}
                          className="crossing-lilypad"
                          transform={`translate(${column * CROSSING.cell + 25} ${y + 25})`}
                        >
                          <ellipse
                            rx="18"
                            ry="12"
                            fill="#668f55"
                            stroke="#496d42"
                            strokeWidth="2"
                          />
                          <path d="M0 0L18-8V8Z" fill="#78aaa8" />
                          {column % 5 === 0 && (
                            <circle cx="-4" cy="-4" r="4" fill="#f3e5d5" />
                          )}
                        </g>
                      ))}
                    </>
                  ) : (
                    Array.from(
                      { length: Math.ceil(CROSSING.columns / 2) },
                      (_, tree) => {
                        const worldWidth = CROSSING.columns * CROSSING.cell;
                        const x =
                          ((lane.id * 83 + tree * 117) % (worldWidth - 20)) +
                          10;
                        return (
                          <g
                            key={tree}
                            className="crossing-tree"
                            transform={`translate(${x} ${y + 8})`}
                            opacity=".72"
                          >
                            <rect
                              x="-2"
                              y="17"
                              width="4"
                              height="16"
                              fill="#70583a"
                            />
                            <circle cy="13" r="11" fill="#6f8c50" />
                          </g>
                        );
                      },
                    )
                  )}
                </g>
              );
            })}
            <g
              className="crossing-player"
              transform={`translate(${world.playerColumn * CROSSING.cell + 25} ${world.playerRow * CROSSING.cell + 25})`}
            >
              <g key={hop} className="crossing-player-hop">
                <ellipse
                  rx="15"
                  ry="18"
                  fill="#53683b"
                  stroke="#34452c"
                  strokeWidth="3"
                />
                <path
                  d="M0-16Q1-28 11-29"
                  fill="none"
                  stroke="#67482f"
                  strokeWidth="3"
                  strokeLinecap="round"
                />
                <circle cx="-5" cy="-3" r="2" fill="#f8f6df" />
                <circle cx="5" cy="-3" r="2" fill="#f8f6df" />
              </g>
            </g>
          </svg>
  );
}

