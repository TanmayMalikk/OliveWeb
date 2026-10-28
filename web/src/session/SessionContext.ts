// Signed-in users: defines the React session state shared by signed-in pages.
import { createContext, useContext } from "react";
import type {
  Category,
  Cover,
  Enrollment,
  Entry,
  Event,
  History,
  Page,
  Profile,
  Role,
} from "../domain/DomainModels";

export type SessionPhase =
  | "public"
  | "setup"
  | "link"
  | "pin"
  | "safe"
  | "portal";

export type SessionValue = {
  path: string;
  go: (path: string) => void;
  phase: SessionPhase;
  personalLink: string;
  hasEntryToken: boolean;
  profile: Profile | null;
  history: History;
  enrollment: Enrollment | null;
  cover: Cover;
  page: Page;
  setPage: (page: Page) => void;
  busy: boolean;
  message: string;
  clearMessage: () => void;
  perform: (work: () => Promise<void>) => Promise<void>;
  authenticate: (
    username: string,
    password: string,
    role: Role,
    create: boolean,
  ) => Promise<void>;
  completeSetup: (profile: Profile, pin: string) => Promise<void>;
  beginEntry: () => Promise<void>;
  quickExit: () => void;
  searchTrigger: (value: string) => Promise<boolean>;
  unlock: (pin: string) => Promise<void>;
  saveProfile: (profile: Profile, action?: string) => Promise<void>;
  saveEntry: (entry: Entry) => Promise<void>;
  record: (category: Category, event: Event | Entry) => Promise<void>;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  idleSeconds: number | null;
  noteInteraction: () => void;
  cloudMode: boolean;
  onboardingProfile: Profile | null;
};

export const SessionContext = createContext<SessionValue | null>(null);

export const useSession = () => {
  const value = useContext(SessionContext);
  if (!value) throw new Error("Missing session");
  return value;
};
