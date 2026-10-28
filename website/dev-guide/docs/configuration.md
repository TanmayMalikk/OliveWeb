# Configuration

The settings for the build are all inside the .gradle and should work across all systems.

## Environment variables

if the file local.properties does not exist already in the DavidsDeals directory,
make that file.
The content should be

```
	sdk.dir=Your/Path/to/Android/SDK
	GEOAPIFY_API_KEY=YOURKEYHERE
	REALTIME_DATABASE_URL=https://olive-21239-default-rtdb.firebaseio.com
```

Example settings:

| Variable | Required | Description | Example |
| --- | --- | --- | --- |
| `APP_ENV` | Yes | Runtime environment | `development` |
| `PORT` | No | Local server port | `3000` |
| `DATABASE_URL` | If using a database | Connection string for your local database | `postgres://localhost/app` |

Replace these examples with the actual names and behavior used by your project. Keep `.env` out of version control.

## Local versus production

Document which values differ between development, testing, and production. Production secrets should be configured in your hosting provider's secret manager, not committed to the repository.

## Troubleshooting

### The app cannot connect to a service

Check that the service is running and that its URL and credentials are set correctly in your local environment.

### A setting appears to be ignored

Restart the development server after changing environment variables, and confirm the variable name matches the spelling and casing expected by the code.
