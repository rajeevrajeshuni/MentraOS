import { noMicrophoneWarn, locationWarn, baackgroundLocationWarn, calendarWarn, readNotficationWarn, postNotficationWarn, cameraWarn } from "src/constants/log-messages/warning";
import { PackagePermissions, Permission } from "src/types/messages/cloud-to-app";


export const microPhoneWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    console.log(`Fetching permissions from: ${permissionsUrl}`);
    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            console.log("Fetched permissions:", data.permissions);
            const hasMic = data.permissions.some((p: Permission) => p.type === "MICROPHONE");
            console.log("Has microphone:", hasMic);

            if (!hasMic) {
                console.log(noMicrophoneWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            // Silently fail if endpoint is unreachable - don't block execution
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
}

export const locationWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            const hasLocation = data.permissions.some((p: Permission) => p.type === "LOCATION");

            if (!hasLocation) {
                console.log(locationWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
}

export const backgroundLocationWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            const hasBackgroundLocation = data.permissions.some((p: Permission) => p.type === "BACKGROUND_LOCATION");

            if (!hasBackgroundLocation) {
                console.log(baackgroundLocationWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
}

export const calendarWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            const hasCalendar = data.permissions.some((p: Permission) => p.type === "CALENDAR");

            if (!hasCalendar) {
                console.log(calendarWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
}

export const readNotificationWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            const hasReadNotifications = data.permissions.some((p: Permission) => p.type === "READ_NOTIFICATIONS");

            if (!hasReadNotifications) {
                console.log(readNotficationWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
}

export const postNotificationWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            const hasPostNotifications = data.permissions.some((p: Permission) => p.type === "POST_NOTIFICATIONS");

            if (!hasPostNotifications) {
                console.log(postNotficationWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
}

export const cameraWarnLog = (cloudServerUrl: string, packageName: string, funcName?: string) => {
    if (!cloudServerUrl) return;

    const permissionsUrl = `${cloudServerUrl}/api/public/permissions/${encodeURIComponent(packageName)}`;

    fetch(permissionsUrl)
        .then(async res => {
        const contentType = res.headers.get('content-type');
        if (!res.ok) {
            console.warn(`Permission API returned ${res.status}: ${res.statusText}`);
            return null;
        }

        if (contentType && contentType.includes('application/json')) {
            return (await res.json()) as PackagePermissions;
        } else {
            const text = await res.text();
            console.warn(`Permission API returned non-JSON response: ${text}`);
            return null;
        }
        })
        .then((data: PackagePermissions | null) => {
            if (data) {
            const hasCamera = data.permissions.some((p: Permission) => p.type === "CAMERA");

            if (!hasCamera) {
                console.log(cameraWarn(funcName, packageName));
            }
            }
        })
        .catch(err => {
            console.debug("Permission check skipped - endpoint unreachable:", err.message);
        });
} 