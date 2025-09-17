import { noMicrophoneWarn } from "src/constants/log-messages/warning";
import { PackagePermissions, Permission } from "src/types/messages/cloud-to-app";


export const  microPhoneWarnLog = (cloudServerUrl: string, packageName:string, onTranscriptionName?:string, ) => {
    if (cloudServerUrl) {
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
            // console.log("Fetched permissions:", data.permissions);
            const hasMic = data.permissions.some((p: Permission) => p.type === "MICROPHONE");
            // console.log("Has microphone:", hasMic);
            
            if (!hasMic) {
                console.log(noMicrophoneWarn(onTranscriptionName, packageName));
            }
            }
        })
        .catch(err => console.error("Permission fetch failed:", err));
    }

} 