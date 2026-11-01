// Public web visitors: downloads the Android test app or installs the iOS web app.
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
import { Logo, Button, Forward, External } from "../components/SharedComponents";
import { useSession } from "../session/SessionProvider";
import { siteHref } from "../sites/SiteRouting";

const ANDROID_DOWNLOAD_URL =
  import.meta.env.VITE_ANDROID_DOWNLOAD_URL?.trim() ||
  "https://firebasestorage.googleapis.com/v0/b/olive-21239.firebasestorage.app/o/public%2Fandroid%2Folive-branch.apk?alt=media";

export default function DownloadPage() {
  const { go } = useSession();
  const [androidQr, setAndroidQr] = useState("");

  useEffect(() => {
    let active = true;
    void QRCode.toDataURL(ANDROID_DOWNLOAD_URL, {
      width: 280,
      margin: 2,
      color: { dark: "#30432b", light: "#ffffff" },
    }).then((value) => {
      if (active) setAndroidQr(value);
    });
    return () => {
      active = false;
    };
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
            <h2>Download the Android test app.</h2>
            <p>
              Scan the code with your Android camera to download the newest
              debug APK, then open the downloaded file to install it. Android
              may ask you to allow installation from your browser.
            </p>
            <div className="qr-area">
              {androidQr ? (
                <>
                  <img
                    src={androidQr}
                    width="240"
                    height="240"
                    alt="QR code linking to the Android debug APK download"
                  />
                  <small>Scan with your Android phone’s camera.</small>
                </>
              ) : (
                <div className="qr-placeholder" aria-live="polite">
                  <Smartphone size={40} />
                  <span>Preparing the Android download code…</span>
                </div>
              )}
            </div>
            <a className="button" href={ANDROID_DOWNLOAD_URL}>
              <Download size={17} /> Download Android debug APK
            </a>
            <p className="help-note">
              This is a school-project debug build, not a Play Store release.
            </p>
          </section>
          <section className="install-card">
            <div className="install-icon">
              <Smartphone size={30} />
            </div>
            <span className="eyebrow">FOR IPHONE & IPAD</span>
            <h2>A place on your Home Screen.</h2>
            <p>
              Install the web app from Safari. It opens from your Home Screen
              like an app, and no App Store download is needed.
            </p>
            <ol className="install-steps">
              <li>
                <span>1</span>
                <div>
                  <h3>Open your chosen cover in Safari</h3>
                  <p>
                    Finish account setup first, then open your cover link in
                    Safari. These installation steps are for Safari on iOS.
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
