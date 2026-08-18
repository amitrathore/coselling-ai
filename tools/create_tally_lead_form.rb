#!/usr/bin/env ruby
# Provision the public coselling.ai lead form from an existing Tally block schema.

require "json"
require "net/http"
require "securerandom"
require "uri"

API_ROOT = "https://api.tally.so"
API_VERSION = "2025-02-01"
FORM_NAME = "Find your place in the coselling.ai network"
TEMPLATE_FORM_ID = ENV.fetch("TALLY_TEMPLATE_FORM_ID", "NpxaMO")

token = ENV.fetch("TALLY_SO_API_KEY")

def request_json(token, method, path, body = nil)
  uri = URI.join(API_ROOT, path)
  request = method.new(uri)
  request["Authorization"] = "Bearer #{token}"
  request["tally-version"] = API_VERSION
  request["Content-Type"] = "application/json" if body
  request.body = JSON.generate(body) if body

  response = Net::HTTP.start(uri.host, uri.port, use_ssl: true) do |http|
    http.request(request)
  end
  unless response.is_a?(Net::HTTPSuccess)
    warn "Tally API request failed (#{response.code}): #{response.body}"
    exit 1
  end
  JSON.parse(response.body)
end

def deep_copy(value)
  JSON.parse(JSON.generate(value))
end

def set_copy(block, text)
  block["payload"]["safeHTMLSchema"] = [[text]]
  block["payload"].delete("html")
end

def choice_blocks(example, group_uuid, choices, type)
  choices.each_with_index.map do |choice, index|
    block = deep_copy(example)
    block["type"] = type
    block["groupUuid"] = group_uuid
    block["payload"]["index"] = index
    block["payload"]["isFirst"] = index.zero?
    block["payload"]["isLast"] = index == choices.length - 1
    block["payload"]["text"] = choice
    block["payload"]["isOtherOption"] = false
    block["payload"]["hasOtherOption"] = false if type == "CHECKBOX"
    block
  end
end

forms = request_json(token, Net::HTTP::Get, "/forms?limit=100")
existing = forms.fetch("items", forms.fetch("forms", [])).find { |form| form["name"] == FORM_NAME }
if existing
  current = request_json(token, Net::HTTP::Get, "/forms/#{existing.fetch("id")}")
  settings = deep_copy(current.fetch("settings"))
  settings["styles"]["font"] = {
    "provider" => "Google",
    "family" => "DM Sans",
    "weight" => settings["styles"].dig("font", "weight")
  }
  updated = request_json(
    token,
    Net::HTTP::Patch,
    "/forms/#{existing.fetch("id")}",
    { "settings" => settings }
  )
  puts JSON.generate(updated.slice("id", "name", "status", "workspaceId"))
  exit
end

template = request_json(token, Net::HTTP::Get, "/forms/#{TEMPLATE_FORM_ID}")
source = template.fetch("blocks")

title = deep_copy(source[0])
title["payload"]["title"] = FORM_NAME
title["payload"]["safeHTMLSchema"] = [[FORM_NAME]]
title["payload"]["button"] = { "label" => "Start the conversation" }

hidden = deep_copy(source[1])
hidden["payload"]["hiddenFields"] = %w[source origin_page ref utm_source utm_medium utm_campaign].map do |name|
  { "uuid" => SecureRandom.uuid, "name" => name }
end

intro = deep_copy(source[2])
set_copy(intro, "Tell us what you bring to the network and what you want to unlock. We review every serious fit and route it to the right conversation.")

role_title = deep_copy(source[13])
set_copy(role_title, "I’m here as a…")
roles = choice_blocks(
  source[14],
  source[14]["groupUuid"],
  [
    "Brand or merchant",
    "Creator or coseller",
    "Community or professional network",
    "Publisher or media company",
    "Marketplace or network operator",
    "Technology or commerce partner"
  ],
  "MULTIPLE_CHOICE_OPTION"
)

name_title, name_input = deep_copy(source[3]), deep_copy(source[4])
set_copy(name_title, "Your name")
name_input["payload"]["placeholder"] = "First and last name"

email_title, email_input = deep_copy(source[5]), deep_copy(source[6])
set_copy(email_title, "Work email")
email_input["payload"]["placeholder"] = "you@company.com"

org_title, org_input = deep_copy(source[7]), deep_copy(source[8])
set_copy(org_title, "Company, community, publication, or handle")
org_input["payload"]["placeholder"] = "The name people know you by"

url_title, url_input = deep_copy(source[9]), deep_copy(source[10])
set_copy(url_title, "Website or primary profile")
url_input["payload"]["placeholder"] = "https://"
url_input["payload"]["isRequired"] = false

reach_title, reach_input = deep_copy(source[11]), deep_copy(source[12])
set_copy(reach_title, "Who do you reach today?")
reach_input["payload"]["placeholder"] = "Describe your audience, customers, members, reach, or distribution footprint."

outcome_title = deep_copy(source[18])
set_copy(outcome_title, "What do you want to make happen?")
outcomes = choice_blocks(
  source[19],
  source[19]["groupUuid"],
  [
    "Grow sales through trusted recommendations",
    "Turn influence into measurable revenue",
    "Add native commerce to a community or publication",
    "Launch or operate a commerce network",
    "Connect products, payments, attribution, or checkout",
    "Explore a strategic partnership"
  ],
  "CHECKBOX"
)
outcomes.first["payload"]["hasMinChoices"] = true
outcomes.first["payload"]["minChoices"] = 1
outcomes.first["payload"]["hasMaxChoices"] = true
outcomes.first["payload"]["maxChoices"] = 3

timing_title = deep_copy(source[25])
set_copy(timing_title, "How soon would you like to move?")
timings = choice_blocks(
  source[26],
  source[26]["groupUuid"],
  [
    "Ready to pilot in 30–60 days",
    "Planning for the next 3–6 months",
    "Already live and ready to scale",
    "Exploring the opportunity"
  ],
  "DROPDOWN_OPTION"
)

context_title, context_input = deep_copy(source[30]), deep_copy(source[31])
set_copy(context_title, "What would make this worth a conversation?")
context_input["payload"]["placeholder"] = "Share the opportunity, constraint, or question that matters most."
context_input["payload"]["isRequired"] = false

captcha = deep_copy(source[32])
blocks = [
  title, hidden, intro,
  role_title, *roles,
  name_title, name_input,
  email_title, email_input,
  org_title, org_input,
  url_title, url_input,
  reach_title, reach_input,
  outcome_title, *outcomes,
  timing_title, *timings,
  context_title, context_input,
  captcha
]

group_ids = {}
blocks.each do |block|
  old_group = block["groupUuid"] || block["uuid"]
  group_ids[old_group] ||= SecureRandom.uuid
  block["groupUuid"] = group_ids[old_group]
  block["uuid"] = SecureRandom.uuid
end

settings = deep_copy(template.fetch("settings"))
settings["styles"]["color"] = {
  "background" => "#ffffff",
  "text" => "#111936",
  "accent" => "#315df5",
  "buttonBackground" => "#ffd22e",
  "buttonText" => "#0b2056"
}
settings["styles"]["font"]["family"] = "DM Sans"
settings["metaSiteName"] = "coselling.ai"
settings["metaTitle"] = FORM_NAME
settings["metaDescription"] = "Tell us where you fit in the community commerce network and what you want to unlock."
settings["hasProgressBar"] = true
settings["saveForLater"] = false

created = request_json(
  token,
  Net::HTTP::Post,
  "/forms",
  {
    "status" => "PUBLISHED",
    "workspaceId" => template.fetch("workspaceId"),
    "blocks" => blocks,
    "settings" => settings
  }
)
puts JSON.generate(created.slice("id", "name", "status", "workspaceId"))
