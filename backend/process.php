<?php
declare(strict_types=1);

/**
 * Also serves anonymous (no Google login) requests identified by install_id — see the
 * install_id/$identity handling below and get_credits.php. Requires these schema additions on top
 * of the original CREATE TABLE in readme.md:
 *   ALTER TABLE purchase_log MODIFY email VARCHAR(254) NULL, ADD COLUMN install_id VARCHAR(64) NULL AFTER email;
 * (plus the install_credits table — see get_credits.php's docblock.)
 */

// SECURITY: this must be false on any deployed server — it was found hardcoded to `true` in the
// live file (2026-08-24). With it on, PHP errors and raw PDO error info are echoed straight into
// the HTTP response to whoever sent the request (see the print_r($pdo->errorInfo()) below and
// display_errors), and every request's raw body gets written to a fixed local path. Flip it on
// only temporarily on your own machine while debugging, never in a commit/deploy.
$debug = false;

if (!$debug)
    error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING);
ini_set('display_errors', ($debug ? '1' : '0'));
ini_set('log_errors', '1');

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');

require_once __DIR__ . '/config.php';

function json_response(array $data, int $httpCode = 200): void {
    http_response_code($httpCode);
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

/** One line of NDJSON (the streaming response format — see the module docblock at the bottom of
 *  this file... actually see readme.md's "Streaming" section), flushed immediately so nginx/php-fpm
 *  don't sit on it waiting for more output (see the buffering-disable calls right before this is
 *  first used). */
function ndjson_line(array $data): void {
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . "\n";
    if (ob_get_level() > 0) { @ob_flush(); }
    @flush();
}

/**
 * Deducts $cost from the caller's credit balance (purchased first, then free — same split logic
 * for both the streaming and non-streaming paths, factored out here so it can't drift between
 * them) and logs the request to purchase_log. Returns the post-deduction balances.
 */
function deduct_credits_and_log(
    PDO $pdo, string $identity, string $email, string $install_id, string $prompt_id,
    int $cost, int $free_credits, int $purchased_credits,
    string $returned_model_name, int $input_tokens, int $output_tokens, ?string $stats_json, float $cogs
): array {
    $cost_purchased = 0;
    $cost_free = 0;
    if ($purchased_credits >= $cost) {
        $cost_purchased = $cost;
        $purchased_credits -= $cost;
    } else {
        $cost_purchased = $purchased_credits;
        $cost_free = $cost - $purchased_credits;
        $purchased_credits = 0;
        $free_credits -= $cost_free;
    }

    $pdo->beginTransaction();
    if ($identity === 'account') {
        $updateStmt = $pdo->prepare('UPDATE users SET free_credits = :fc, purchased_credits = :pc, updated_at = CURRENT_TIMESTAMP WHERE email = :email');
        $updateStmt->execute([':fc' => $free_credits, ':pc' => $purchased_credits, ':email' => $email]);
    } else {
        $updateStmt = $pdo->prepare('UPDATE install_credits SET credits = :fc, updated_at = CURRENT_TIMESTAMP WHERE install_id = :install_id');
        $updateStmt->execute([':fc' => $free_credits, ':install_id' => $install_id]);
    }

    $logStmt = $pdo->prepare('INSERT INTO purchase_log (email, install_id, prompt_id, cost_free, cost_purchased, model_name, input_tokens, output_tokens, stats_json, cogs) VALUES (:email, :install_id, :prompt_id, :cf, :cp, :model_name, :input_tokens, :output_tokens, :stats_json, :cogs)');
    $logStmt->execute([
        ':email' => $identity === 'account' ? $email : null,
        ':install_id' => $identity === 'anon' ? $install_id : null,
        ':prompt_id' => $prompt_id,
        ':cf' => $cost_free,
        ':cp' => $cost_purchased,
        ':model_name' => $returned_model_name,
        ':input_tokens' => $input_tokens,
        ':output_tokens' => $output_tokens,
        ':stats_json' => $stats_json,
        ':cogs' => $cogs
    ]);
    $pdo->commit();

    return ['free_credits' => $free_credits, 'purchased_credits' => $purchased_credits];
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    if ($debug)
        $input = json_decode(file_get_contents('/home/wikipond/lense_whisper/input.json'), true);
    else
        json_response(['error' => true, 'model_response' => 'Method not allowed. Please use POST.', 'cost' => 0], 405);
} else {
    $input = json_decode(file_get_contents('php://input'), true);
    if ($debug)
        file_put_contents('/home/wikipond/lense_whisper/input.json', file_get_contents('php://input'));
}

if (!$input) {
    json_response(['error' => true, 'model_response' => 'Invalid JSON payload received.', 'cost' => 0], 400);
}

// 1. Extract & Sanitize Inputs
$email = filter_var($input['email'] ?? '', FILTER_SANITIZE_EMAIL);
$google_id = preg_replace('/[^a-zA-Z0-9\-_]/', '', $input['google_id'] ?? '');
// Anonymous (no-login) identity — see get_credits.php. The app currently has no login UI at all,
// so in practice $email/$google_id above are always empty and every request takes this path; that
// code is kept intact rather than deleted so logging back in can be re-wired later without redoing
// the credit-deduction logic. See LOGIN_AND_PURCHASES_REMOVED.md at the repo root.
$install_id = preg_replace('/[^a-zA-Z0-9\-]/', '', $input['install_id'] ?? '');
$prompt_id = preg_replace('/[^a-zA-Z0-9_]/', '', $input['prompt_id'] ?? '');
$language = htmlspecialchars(trim($input['language'] ?? 'en-US'), ENT_QUOTES, 'UTF-8');
$persona = htmlspecialchars(trim($input['persona'] ?? 'Neutral'), ENT_QUOTES, 'UTF-8');
$quality = htmlspecialchars(trim($input['quality'] ?? 'standard'), ENT_QUOTES, 'UTF-8');
$frames = is_array($input['frames'] ?? null) ? $input['frames'] : [];
$custom_question = isset($input['custom_question']) ? substr(htmlspecialchars(trim($input['custom_question']), ENT_QUOTES, 'UTF-8'), 0, 200) : '';
// Client opt-in (Settings, default off — see PreferencesManager.streamingEnabled). Ignored (forced
// false) for Explore below once $expect_json is known — a JSON grid response isn't sentence-shaped
// and streaming it token-by-token would just hand the client a stream of un-parseable fragments.
$streaming = filter_var($input['streaming'] ?? false, FILTER_VALIDATE_BOOLEAN);

// 2. Validate Persona
$allowed_personas = ['Neutral', 'Family', 'Childish', 'Technical', 'explicit', 'explicit and naughty', 'Humorous', 'poetry'];
if (!in_array($persona, $allowed_personas, true)) {
    json_response(['error' => true, 'model_response' => 'Invalid persona mode selected.', 'cost' => 0], 400);
}

// 3. Validate Frames 
if (count($frames) === 0 || count($frames) > 5) {
    json_response(['error' => true, 'model_response' => 'Invalid frame count. You must send between 1 and 5 frames.', 'cost' => 0], 400);
}
foreach ($frames as $frame) {
    if (strlen($frame) > 135000) {
        json_response(['error' => true, 'model_response' => 'Media resolution too high. Please try again.', 'cost' => 0], 400);
    }
}

try {
    $pdo = get_pdo_connection();

    // Rates per 1,000,000 tokens (Update these matching your API provider's actual pricing)
    $token_rates_per_m = [
        'gemini-2.5-flash-lite' => ['in' => 0.075, 'out' => 0.30],
        'grok-4.3'              => ['in' => 2.00,  'out' => 10.00]
    ];

    // 4. Calculate Cost and Determine Model Configuration BEFORE checking credits
    // Explore mode counts exactly as 5 frames for cost logic
    $effective_frame_count = ($prompt_id === 'explore') ? 5 : count($frames);

    if (strtolower($quality) === 'low' || strtolower($quality) === 'standard') {
        $model_name = "gemini-2.5-flash-lite";
        $cost = $effective_frame_count * 1;
        
        $api_endpoint = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
        $api_key = defined('GEMINI_API_KEY') ? GEMINI_API_KEY : ''; 
    } else {
        $model_name = "grok-4.3";
        $cost = $effective_frame_count * 2;
        
        $api_endpoint = 'https://api.x.ai/v1/chat/completions';
        $api_key = defined('XAI_API_KEY') ? XAI_API_KEY : '';
    }

    // 5. Verify User and Credits
    // Two mutually-exclusive identity paths: a logged-in `users` row (email+google_id — dormant
    // while the app has no login UI, see install_id comment above) or an anonymous
    // `install_credits` row (install_id, the only path currently reachable from the app). Shared
    // with get_credits.php via resolve_identity() (config.php) so the two can't drift apart.
    $resolved = resolve_identity($pdo, $email, $google_id, $install_id);
    if ($resolved === null) {
        json_response(['error' => true, 'model_response' => 'Unauthorized. Please log in again or retry in a moment.', 'cost' => 0], 401);
    }
    $identity = $resolved['type']; // 'account' | 'anon'
    $free_credits = $resolved['free_credits'];
    $purchased_credits = $resolved['purchased_credits'];

    // Check dynamic cost
    if (($free_credits + $purchased_credits) < $cost) {
        json_response(['error' => true, 'model_response' => "Insufficient credits. You need at least $cost credits to execute this action.", 'cost' => 0, 'free_credits' => $free_credits, 'purchased_credits' => $purchased_credits], 403);
    }

    // 6. Build the AI System Prompt
    $is_video = count($frames) > 1;
    $system_prompt = "";
    $expect_json = false;
    $user_prompt = "Process the image(s).";

    if ($prompt_id === 'explore') {
        $expect_json = true;
        $streaming = false; // structured grid JSON, not sentence-shaped — see the docblock above.
        $gridSize = 6;
        $system_prompt = "You are a precise spatial mapping AI for a visually impaired user's screen-reader app. " .
                "CRITICAL COMPLIANCE: All descriptions assigned to grid keys MUST be written ENTIRELY in $language. " .
                "Limit each description to a few words. " .
                "You must divide the image into a mathematically strict {$gridSize}x{$gridSize} physical grid. " .
                "Respond using a $persona description style. " .
                "You MUST respond ONLY with a flat JSON object where the keys are labeled exactly from 'row1_col1' through 'row{$gridSize}_col{$gridSize}'.";
        $user_prompt = "Generate the JSON map spanning the entire image.";
    } elseif ($prompt_id === 'custom') {
        $system_prompt = "CRITICAL COMPLIANCE: You MUST write your ENTIRE response in the language $language.\n" .
            "You are a precise visual question-answering assistant for a visually impaired user.\n" .
            "THE USER'S EXACT QUESTION IS: \"$custom_question\"\n" .
            "Answer ONLY the user's exact question above clearly and concisely. If the requested object is not in view, explicitly state that.";
        $user_prompt = $custom_question;
    } else {
        $predefined_prompts = [
            'brief_narration' => 'Describe this scene very briefly in a single short sentence. If the scene is totally empty, explicitly state that.',
            'detailed_scene' => 'Describe this scene in detail, including people, objects, settings, and overall atmosphere. If nothing distinct is visible, explicitly state that.',
            'people_and_outfit' => 'Describe the outfit, clothing, body appearance, and approximate age of the main people visible. If no people are visible, you MUST explicitly state \'No people are visible in this scene\'.',
            'emotions' => 'Analyze and describe the facial expressions and emotions of each main visible person. If no people or faces are visible, state \'No people or faces are visible\'.',
            'objects' => 'Identify and list the main physical objects visible and describe their layout or characteristics. Only list the distinct physical objects.',
            'read_texts' => 'Identify any visible signs, textual labels, documents, or screen characters and read the text precisely. If absolutely no text visible, state \'No text found\'.',
            'story_5' => 'Tell the continuous story of what is happening across these sequence frames as a single fluid narrative. If no action, state \'No action detected\'.',
            'story_10' => 'Tell the continuous story of what is happening across these sequence frames as a single fluid narrative. If no action, state \'No action detected\'.'
        ];

        $user_prompt = $predefined_prompts[$prompt_id] ?? 'Describe the image.';
        
        $system_prompt = "CRITICAL COMPLIANCE: You MUST write your response ENTIRELY in the language: $language. " .
            "Limit your entire description to a maximum of 100 words. " .
            "Respond using a $persona narration style. You are an expert audio describer for the visually impaired.";
            
        if ($is_video) {
            $system_prompt .= " The user has sent multiple consecutive video frames capturing a timeline sequence. Describe the scene's movement and actions over time smoothly.";
        }
    }

    // 7. Execute AI API Call (Dynamic Endpoint)
    $contentArray = [];
    $contentArray[] = ["type" => "text", "text" => $user_prompt];
    foreach ($frames as $base64Str) {
        $imgObj = ["url" => "data:image/jpeg;base64," . $base64Str];
        if (count($frames) > 1) { $imgObj["detail"] = "low"; }
        $contentArray[] = ["type" => "image_url", "image_url" => $imgObj];
    }

    $payload = [
        "model" => $model_name,
        "messages" => [
            ["role" => "system", "content" => $system_prompt],
            ["role" => "user", "content" => $contentArray]
        ]
    ];
    if ($expect_json) {
        $payload["response_format"] = ["type" => "json_object"];
    }
    
    // Set minimum reasoning effort for Grok 4.3
    if ($model_name === 'grok-4.3') {
        $payload["reasoning_effort"] = "low";
    }

    // Shared token->$ rate lookup, used by both paths below to compute cogs from whatever usage
    // stats we end up with.
    $rateFor = function (string $returnedModelName) use ($token_rates_per_m, $model_name): array {
        foreach ($token_rates_per_m as $key => $rates) {
            // Match string dynamically in case API appends version suffixes (e.g. gemini-2.5-flash-lite-02-14)
            if (strpos(strtolower($returnedModelName), $key) !== false || $model_name === $key) {
                return [$rates['in'], $rates['out']];
            }
        }
        return [0, 0];
    };

    if ($streaming) {
        // --- Streaming path: relay the upstream SSE stream to our own client as NDJSON, one
        // `{"delta": "..."}` line per content fragment, flushed immediately. Credits are still
        // deducted and logged exactly once, after the stream finishes — the flat per-frame $cost
        // above never depended on token usage, only $cogs (analytics only) does, and that's
        // computed from whatever `usage` the last SSE chunk carried (best-effort: some providers
        // omit it even with stream_options.include_usage requested, in which case cogs is 0 — same
        // as the non-streaming path already tolerates via `$usage = $res_json['usage'] ?? []`).
        header('Content-Type: application/x-ndjson; charset=utf-8');
        header('X-Accel-Buffering: no'); // tells nginx not to buffer this response
        header('Cache-Control: no-cache');
        while (ob_get_level() > 0) { @ob_end_flush(); }
        ini_set('output_buffering', 'off');
        ini_set('implicit_flush', '1');
        ob_implicit_flush(true);

        $payload['stream'] = true;
        $payload['stream_options'] = ['include_usage' => true];

        $sseBuffer = '';
        $accumulated = '';
        $capturedUsage = null;
        $capturedModelName = null;

        $ch = curl_init($api_endpoint);
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => json_encode($payload),
            CURLOPT_HTTPHEADER => [
                "Authorization: Bearer " . $api_key,
                "Content-Type: application/json"
            ],
            CURLOPT_TIMEOUT => 90,
            // Called repeatedly as bytes arrive from upstream — this is what makes it a relay
            // instead of a buffer-then-forward. Must return the byte count consumed, or curl
            // treats a short return as an abort.
            CURLOPT_WRITEFUNCTION => function ($curlHandle, $chunk) use (&$sseBuffer, &$accumulated, &$capturedUsage, &$capturedModelName) {
                $sseBuffer .= $chunk;
                while (($nlPos = strpos($sseBuffer, "\n")) !== false) {
                    $line = trim(substr($sseBuffer, 0, $nlPos));
                    $sseBuffer = substr($sseBuffer, $nlPos + 1);
                    if ($line === '' || strpos($line, 'data:') !== 0) continue;
                    $data = trim(substr($line, 5));
                    if ($data === '[DONE]') continue;
                    $chunkJson = json_decode($data, true);
                    if (!is_array($chunkJson)) continue;
                    if (isset($chunkJson['usage']) && is_array($chunkJson['usage'])) $capturedUsage = $chunkJson['usage'];
                    if (isset($chunkJson['model'])) $capturedModelName = $chunkJson['model'];
                    $delta = $chunkJson['choices'][0]['delta']['content'] ?? null;
                    if (is_string($delta) && $delta !== '') {
                        $accumulated .= $delta;
                        ndjson_line(['delta' => $delta]);
                    }
                }
                return strlen($chunk);
            },
        ]);
        $curlOk = curl_exec($ch);
        $http_code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        if ($curlOk === false || $http_code !== 200) {
            // If some deltas already went out, the client still gets a clean error line telling it
            // to stop and treat what it received as incomplete/discard it — see
            // OfflineInferenceEngine.analyzeStreaming's client-side doc for the equivalent contract.
            ndjson_line(['error' => true, 'model_response' => 'Error communicating with AI server. Please try again.']);
            exit;
        }

        $returned_model_name = $capturedModelName ?? $model_name;
        $usage = $capturedUsage ?? [];
        $input_tokens = (int)($usage['prompt_tokens'] ?? 0);
        $output_tokens = (int)($usage['completion_tokens'] ?? 0);
        $stats_json = empty($usage) ? null : json_encode($usage);
        [$rate_in, $rate_out] = $rateFor($returned_model_name);
        $cogs = (($input_tokens / 1000000) * $rate_in) + (($output_tokens / 1000000) * $rate_out);

        $balances = deduct_credits_and_log(
            $pdo, $identity, $email, $install_id, $prompt_id, $cost, $free_credits, $purchased_credits,
            $returned_model_name, $input_tokens, $output_tokens, $stats_json, $cogs
        );
        ndjson_line(['done' => true, 'cost' => $cost, 'free_credits' => $balances['free_credits'], 'purchased_credits' => $balances['purchased_credits']]);
        exit;
    }

    // --- Non-streaming path (unchanged behavior) ---
    $ch = curl_init($api_endpoint);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode($payload),
        CURLOPT_HTTPHEADER => [
            "Authorization: Bearer " . $api_key,
            "Content-Type: application/json"
        ],
        CURLOPT_TIMEOUT => 60
    ]);

    $response = curl_exec($ch);
    $http_code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($http_code !== 200 || !$response) {
        json_response(['error' => true, 'model_response' => 'Error communicating with AI server. Please try again.', 'cost' => 0], 502);
    }

    if ($debug)
        file_put_contents('/home/wikipond/lense_whisper/output.json', $response);

    $res_json = json_decode($response, true);
    $model_response = $res_json['choices'][0]['message']['content'] ?? 'Error parsing AI response.';

    // Extract actual model name returned by the API (fallback to intended model)
    $returned_model_name = $res_json['model'] ?? $model_name;

    // 8. Extract Token Usage Stats
    $usage = $res_json['usage'] ?? [];
    $input_tokens = (int)($usage['prompt_tokens'] ?? 0);
    $output_tokens = (int)($usage['completion_tokens'] ?? 0);
    $stats_json = empty($usage) ? null : json_encode($usage);

    // Calculate actual COGS based on returned model and token usage
    [$rate_in, $rate_out] = $rateFor($returned_model_name);
    $cogs = (($input_tokens / 1000000) * $rate_in) + (($output_tokens / 1000000) * $rate_out);

    // 9. Deduct Credits & Log — email is NULL for anonymous requests, install_id stored instead
    // (see deduct_credits_and_log) so usage is still traceable per-device.
    $balances = deduct_credits_and_log(
        $pdo, $identity, $email, $install_id, $prompt_id, $cost, $free_credits, $purchased_credits,
        $returned_model_name, $input_tokens, $output_tokens, $stats_json, $cogs
    );

    json_response([
        'error' => false,
        'model_response' => $model_response,
        'cost' => $cost,
        'free_credits' => $balances['free_credits'],
        'purchased_credits' => $balances['purchased_credits']
    ]);

} catch (Throwable $e) {
    if (isset($pdo) && $pdo->inTransaction()) { $pdo->rollBack(); }
    error_log('process.php error: ' . $e->getMessage());
    if ($debug && isset($pdo))
        print_r($pdo->errorInfo());
    json_response(['error' => true, 'model_response' => 'Internal server database error.', 'cost' => 0], 500);
}
?>