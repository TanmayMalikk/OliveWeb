// Staff and admins: saves profile updates for organization accounts through the protected cloud function.
import { cloudMode, firebase } from "./AccountService";
import type { Profile } from "../domain/DomainModels";

export async function saveOrganizationProfile(input: {
  fullName: string;
  dob: string;
  email: string;
  phone: string;
}): Promise<Profile> {
  if (!cloudMode) {
    throw new Error("Organization profile updates require a Firebase-connected account.");
  }
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  const result = (
    await httpsCallable(functions, "completeOrganizationProfile")(input)
  ).data as { profile: Profile };
  return result.profile;
}
