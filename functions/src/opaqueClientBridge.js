const opaque = require("@serenity-kit/opaque");

window.opaqueBridge = {
  isReady: false,
  init: async function() {
    await opaque.ready;
    window.opaqueBridge.isReady = true;
    return true;
  },
  startRegistration: async function(password) {
    await opaque.ready;
    const res = opaque.client.createRegistrationRequest({ password });
    return JSON.stringify({
      clientRegistrationState: res.clientRegistrationState,
      registrationRequest: res.registrationRequest
    });
  },
  finishRegistration: async function(state, response, password) {
    await opaque.ready;
    const res = opaque.client.finalizeRegistrationRequest({
      clientRegistrationState: state,
      registrationResponse: response,
      password: password
    });
    return JSON.stringify({
      registrationRecord: res.registrationRecord,
      exportKey: Array.from(res.exportKey)
    });
  },
  startLogin: async function(password) {
    await opaque.ready;
    const res = opaque.client.startLogin({ password });
    return JSON.stringify({
      clientLoginState: res.clientLoginState,
      startLoginRequest: res.startLoginRequest
    });
  },
  finishLogin: async function(state, response, password) {
    await opaque.ready;
    try {
      const res = opaque.client.finishLogin({
        clientLoginState: state,
        loginResponse: response,
        password: password
      });
      return JSON.stringify({
        finishLoginRequest: res.finishLoginRequest,
        exportKey: Array.from(res.exportKey)
      });
    } catch (e) {
      return JSON.stringify({ error: "incorrect_password" });
    }
  }
};
window.opaqueBridge.init();
