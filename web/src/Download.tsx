import { useEffect, useState } from "react";
import QRCode from "qrcode";
import {
  ArrowLeft,
  ArrowUpRight,
  Download,
  Smartphone,
  Share,
  PlusSquare,
  Check,
} from "lucide-react";
import { Logo, Button, Forward, External } from "./components";
import { useSession } from "./session";
import { siteHref } from "./sites";
interface Distribution {
  androidAvailable: boolean;
  androidUrl: string | null;
  phoneReady: boolean;
  androidLabel?: string | null;
}
export default function DownloadPage() {
  const { go } = useSession();
  const [info, setInfo] = useState<Distribution | null>(null);
  const [qr, setQr] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/distribution", { signal: controller.signal })
      .then(async (res) => {
        if (!res.ok) throw new Error();
        return res.json();
      })
      .then((value: Distribution) => {
        setInfo(value);
        if (value.androidAvailable && value.phoneReady && value.androidUrl)
          return QRCode.toDataURL(value.androidUrl, {
            width: 280,
            margin: 2,
            color: { dark: "#30432b", light: "#ffffff" },
          }).then(setQr);
      })
      .catch((e) => {
        if (e.name !== "AbortError")
          setError("Download information is unavailable. Try again shortly.");
      });
    return () => controller.abort();
  }, []);
  return (
    <div className="page-width download-page">
      <header className="site-header">
        <Logo />
        <nav>
          <a
            href={siteHref("/account")}
            onClick={(e) => {
              e.preventDefault();
              go("/account");
            }}
          >
            Your account <ArrowUpRight size={16} />
          </a>
        </nav>
      </header>
      <main id="main">
        <button className="text-link" onClick={() => go("/")}>
          <ArrowLeft size={16} /> Back to Little Grove
        </button>
        <div className="download-heading">
          <span className="eyebrow">A FAMILIAR PLACE, WHEREVER YOU GO</span>
          <h1>
            Make a little
            <br />
            <em>room on your screen.</em>
          </h1>
          <p>Choose your device. Settle in. Your account goes with you.</p>
        </div>
        <div className="download-grid">
          <section className="install-card">
            <div className="install-icon">
              <Download size={30} />
            </div>
            <span className="eyebrow">FOR ANDROID</span>
            <h2>Your everyday, in an app.</h2>
            {info?.androidLabel && (
              <p className="help-note">{info.androidLabel}</p>
            )}
            <p>
              Download the Android APK and open it to install. Android may ask
              you to allow installation from your browser.
            </p>
            <div className="qr-area">
              {qr ? (
                <>
                  <img
                    src={qr}
                    width="240"
                    height="240"
                    alt="QR code linking to the Android APK download"
                  />
                  <small>Scan with your Android phone’s camera.</small>
                </>
              ) : (
                <div className="qr-placeholder">
                  <Smartphone size={40} />
                  <span>
                    {info?.androidAvailable
                      ? "A phone-accessible QR link is not configured yet."
                      : "The Android download is not available in this local preview yet."}
                  </span>
                </div>
              )}
            </div>
            {info?.androidAvailable ? (
              <a className="button" href="/download/android">
                <Download size={17} /> Download Android APK
              </a>
            ) : (
              <Button disabled>Android download unavailable</Button>
            )}
            {info?.androidAvailable && !info.phoneReady && (
              <p className="help-note">
                You can download on this computer. A phone cannot use this
                computer’s localhost address.
              </p>
            )}
          </section>
          <section className="install-card">
            <div className="install-icon">
              <Smartphone size={30} />
            </div>
            <span className="eyebrow">FOR IPHONE & IPAD</span>
            <h2>A place on your Home Screen.</h2>
            <p>Use the web app with Safari. No App Store download is needed.</p>
            <ol className="install-steps">
              <li>
                <span>1</span>
                <div>
                  <h3>Open your chosen cover in Safari</h3>
                  <p>
                    Finish account setup first, then open the cover linked to
                    your account.
                  </p>
                </div>
              </li>
              <li>
                <span>
                  <Share size={18} />
                </span>
                <div>
                  <h3>Open the Share menu</h3>
                  <p>
                    Tap Safari’s Share button. Its position varies with your
                    Safari layout.
                  </p>
                </div>
              </li>
              <li>
                <span>
                  <PlusSquare size={18} />
                </span>
                <div>
                  <h3>Choose “Add to Home Screen”</h3>
                  <p>
                    Scroll through the actions if needed. Turn on “Open as Web
                    App” if that option appears, then tap Add.
                  </p>
                </div>
              </li>
              <li>
                <span>
                  <Check size={18} />
                </span>
                <div>
                  <h3>Open it from your Home Screen</h3>
                  <p>
                    Keep your personal entry link separately. Installing the web
                    app does not unlock your private space.
                  </p>
                </div>
              </li>
            </ol>
            <External href="https://support.apple.com/guide/iphone/turn-a-website-into-an-app-iphea86e5236/ios">
              Apple’s installation guide
            </External>
          </section>
        </div>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
        <div className="download-account panel">
          <div>
            <span className="eyebrow">NEW HERE?</span>
            <h2>Start with your account.</h2>
            <p>
              Create an account and choose your everyday app before installing.
            </p>
          </div>
          <Button onClick={() => go("/account/create")}>
            <Forward>Begin onboarding</Forward>
          </Button>
        </div>
      </main>
    </div>
  );
}
