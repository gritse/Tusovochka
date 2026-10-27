// Browser push notifications: the "Push notifications" section in Settings → Notifications.
// The service worker that shows them is /sw.js (src/main/resources/public/sw.js).

function isIOSDevice(): boolean {
	// iPadOS reports itself as a Mac, but Macs have no touch screen
	return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform == "MacIntel" && navigator.maxTouchPoints > 1);
}

function isStandaloneApp(): boolean {
	return (navigator as any).standalone === true || window.matchMedia("(display-mode: standalone)").matches;
}

function base64UrlToBytes(s: string): Uint8Array {
	var bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - s.length % 4) % 4));
	var out = new Uint8Array(bin.length);
	for (var i = 0; i < bin.length; i++) {
		out[i] = bin.charCodeAt(i);
	}
	return out;
}

function sameBytes(a: ArrayBuffer, b: Uint8Array): boolean {
	var x = new Uint8Array(a);
	if (x.length != b.length)
		return false;
	for (var i = 0; i < x.length; i++) {
		if (x[i] != b[i])
			return false;
	}
	return true;
}

/**
 * @param vapidPublicKey the server's key, base64url
 * @param knownEndpoints the endpoints the server has for this account, so a subscription it has forgotten shows as off
 */
function initWebPushSettings(vapidPublicKey: string, knownEndpoints: string[]) {
	var status = ge("webPushStatus");
	var enableButton = ge<HTMLButtonElement>("webPushEnable");
	var disableButton = ge<HTMLButtonElement>("webPushDisable");
	var serverKey = base64UrlToBytes(vapidPublicKey);
	var registration: ServiceWorkerRegistration = null;

	var setStatus = (key: string) => {
		status.innerText = lang(key);
	};

	if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
		if (isIOSDevice() && !isStandaloneApp())
			setStatus("settings_web_push_ios_install");
		else if (!window.isSecureContext)
			setStatus("settings_web_push_no_https");
		else
			setStatus("settings_web_push_unsupported");
		return;
	}

	var showState = (sub: PushSubscription) => {
		var on = sub != null && knownEndpoints.indexOf(sub.endpoint) != -1;
		if (!on && Notification.permission == "denied") {
			setStatus("settings_web_push_denied");
			enableButton.style.display = "none";
		} else {
			setStatus(on ? "settings_web_push_this_device_on" : "settings_web_push_this_device_off");
			enableButton.style.display = on ? "none" : "";
		}
		disableButton.style.display = on ? "" : "none";
	};

	var showError = (e: any) => {
		console.error(e);
		new MessageBox(lang("error"), String(e && e.message || e), lang("close")).show();
	};

	navigator.serviceWorker.register("/sw.js").then((reg) => {
		registration = reg;
		return reg.pushManager.getSubscription();
	}).then((sub) => {
		// A subscription made with an older server key can't receive anything any more
		if (sub && sub.options.applicationServerKey && !sameBytes(sub.options.applicationServerKey, serverKey))
			return sub.unsubscribe().then(() => null);
		return sub;
	}).then(showState, (e) => {
		console.error(e);
		setStatus("settings_web_push_unsupported");
	});

	enableButton.onclick = () => {
		if (!registration)
			return;
		enableButton.disabled = true;
		// Safari only shows the permission prompt in response to a tap, so ask before doing anything asynchronous
		Notification.requestPermission().then((permission) => {
			if (permission != "granted")
				return null;
			return registration.pushManager.getSubscription().then((sub) => sub || registration.pushManager.subscribe({userVisibleOnly: true, applicationServerKey: serverKey}));
		}).then((sub) => {
			enableButton.disabled = false;
			if (!sub) {
				showState(null);
				return;
			}
			var json = sub.toJSON();
			ajaxPostAndApplyActions("/settings/webPush/subscribe", {endpoint: sub.endpoint, p256dh: json.keys.p256dh, auth: json.keys.auth}, null, null, true);
		}).catch((e) => {
			enableButton.disabled = false;
			showError(e);
		});
	};

	disableButton.onclick = () => {
		if (!registration)
			return;
		disableButton.disabled = true;
		registration.pushManager.getSubscription().then((sub) => {
			if (!sub)
				return null;
			return sub.unsubscribe().then(() => sub.endpoint);
		}).then((endpoint) => {
			disableButton.disabled = false;
			if (endpoint)
				ajaxPostAndApplyActions("/settings/webPush/unsubscribe", {endpoint: endpoint}, null, null, true);
			else
				showState(null);
		}).catch((e) => {
			disableButton.disabled = false;
			showError(e);
		});
	};
}
