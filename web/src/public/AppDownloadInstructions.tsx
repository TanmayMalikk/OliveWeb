// Public visitors: download the signed Android release or install the iOS web app.
import {
  ArrowLeft,
  ArrowUpRight,
  Download,
  Smartphone,
  Share,
  PlusSquare,
  Check,
} from "lucide-react";
import {
  Logo,
  Button,
  Forward,
  External,
} from "../components/SharedComponents";
import { useSession } from "../session/SessionProvider";
import { siteHref } from "../sites/SiteRouting";
import config from "../../site.config.json";

const ANDROID_DOWNLOAD_URL = new URL("/download/android", location.origin).href;
const ANDROID_DOWNLOAD_PAGE_URL = new URL("/get", location.origin).href;

export default function DownloadPage() {
  const { go } = useSession();

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
          <ArrowLeft size={16} /> Back to Fitness Tracker
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
            <h2>Download the signed Android app.</h2>
            <p>
              Scan the QR code or download the current release on your Android
              device, then open the file to install it. Android may ask you to
              allow installation from your browser.
            </p>
            <a
              className="download-qr"
              href={ANDROID_DOWNLOAD_PAGE_URL}
              aria-label="Open the Android download page"
            >
              <img
                src="/android-download-qr.svg?v=get-page-v1"
                alt="QR code for the latest signed Android app"
              />
            </a>
            <a className="button" href={ANDROID_DOWNLOAD_URL}>
              <Download size={17} /> Download signed APK
            </a>
            <p className="help-note">
              Android may show a warning because this APK is installed outside
              the Play Store.
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
        <section className="download-web panel">
          <div className="install-icon">
            <Smartphone size={30} />
          </div>
          <div>
            <span className="eyebrow">OPEN IN A BROWSER</span>
            <h2>Your space, on the web.</h2>
            <p>
              Sign in with the same account to open the web version of Olive
              Branch.
            </p>
          </div>
          <a className="button" href={`${config.publicOrigin}/account/sign-in`}>
            Open the web app <ArrowUpRight size={17} />
          </a>
        </section>
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
