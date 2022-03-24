# souffleuse
A chatbot that announces meetings and releases on Slack and Twitter

## Configuration

Environment variables:

- `GITHUB_TOKEN` (The token you get from GitHub Webhooks)
- `SLACK_HOOK_URL` (Just a url that is unique for your integration)
- `TWITTER_API_KEY`
- `TWITTER_API_SECRET`
- `TWITTER_ACCESS_TOKEN`
- `TWITTER_ACCESS_TOKEN_SECRET`

## Running

Either via container: https://hub.docker.com/r/replikativ/souffleuse
or the uberjar `java -jar target/souffleuse-0.1.8`.

## Set webhooks

[On GitHub set a webhook](https://github.com/organizations/replikativ/settings/hooks) to send Releases
to a domain where souffleuse is running to the path `/github/release`. Take [the credentials from your
Twitter bot](https://developer.twitter.com/en/portal/dashboard). [Get the URL for your Slack integration](https://clojurians.slack.com/apps/A033SFT699B-replikativ)
from the Slack-bot.
