<?php
declare(strict_types=1);

$debug = false;

if (!$debug)
	error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING);
ini_set('display_errors', ($debug ? '1' : '0'));
ini_set('log_errors', '1');

if (!$debug)
	header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/config.php';

function json_response(array $data, int $httpCode = 200): void {
	http_response_code($httpCode);
	echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
	exit;
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
$prompt_id = preg_replace('/[^a-zA-Z0-9_]/', '', $input['prompt_id'] ?? '');
$language = htmlspecialchars(trim($input['language'] ?? 'en-US'), ENT_QUOTES, 'UTF-8');
$persona = htmlspecialchars(trim($input['persona'] ?? 'Neutral'), ENT_QUOTES, 'UTF-8');
$quality = htmlspecialchars(trim($input['quality'] ?? 'standard'), ENT_QUOTES, 'UTF-8');
$frames = is_array($input['frames'] ?? null) ? $input['frames'] : [];
$custom_question = isset($input['custom_question']) ? substr(htmlspecialchars(trim($input['custom_question']), ENT_QUOTES, 'UTF-8'), 0, 200) : '';

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

	// 4. Model and Pricing Configurations
	$models = [
		//'standard'         => 'gemini-2.5-flash-lite',
		'standard'         => 'gpt-5-nano',
		'high'             => 'grok-4-1-fast-non-reasoning',
		'explore_standard' => 'gpt-5-nano',
		'explore_high'     => 'grok-4-1-fast-non-reasoning',
	];

	$model_credits = [
		'gemini-2.5-flash-lite'        => 1,
		'gpt-5-nano'                   => 1,
		'grok-4-1-fast-non-reasoning'  => 5,
		'grok-4.20-0309-non-reasoning' => 5,
		'grok-4.3'                     => 50
	];

	// Rates per 1,000,000 tokens (USD)
	$token_rates_per_m = [
		'gemini-2.5-flash-lite'        => ['in' => 0.10, 'out' => 0.40],
		'gpt-5-nano'                   => ['in' => 0.05, 'out' => 0.40],
		'grok-4-1-fast-non-reasoning'  => ['in' => 1.25, 'out' => 2.50],
		'grok-4.20-0309-non-reasoning' => ['in' => 1.25, 'out' => 2.50],
		'grok-4.3'                     => ['in' => 1.25, 'out' => 2.50],
	];

	// 5. Determine Target Model and Compute Cost
	$is_explore = ($prompt_id === 'explore');
	$is_standard_quality = (strtolower($quality) === 'low' || strtolower($quality) === 'standard');
	
	// Set effective frame count (explore is always 5)
	$effective_frame_count = $is_explore ? 5 : count($frames);

	if ($is_explore) {
		$model_name = $is_standard_quality ? $models['explore_standard'] : $models['explore_high'];
	} else {
		$model_name = $is_standard_quality ? $models['standard'] : $models['high'];
	}

	// Lookup credit cost multiplier
	$credit_multiplier = 10; // Default to 10 for any unmatched Grok models
	foreach ($model_credits as $key => $val) {
		if (strpos(strtolower($model_name), $key) !== false || $model_name === $key) {
			$credit_multiplier = $val;
			break;
		}
	}
	
	$cost = $effective_frame_count * $credit_multiplier;

	// Determine API Endpoint
	if (strpos(strtolower($model_name), 'gemini') !== false) {
		$api_endpoint = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
		$api_key = defined('GEMINI_API_KEY') ? GEMINI_API_KEY : ''; 
	} elseif (strpos(strtolower($model_name), 'gpt') !== false) {
		$api_endpoint = 'https://api.openai.com/v1/chat/completions';
		$api_key = defined('OPENAI_API_KEY') ? OPENAI_API_KEY : ''; 
	} else {
		$api_endpoint = 'https://api.x.ai/v1/chat/completions';
		$api_key = defined('XAI_API_KEY') ? XAI_API_KEY : '';
	}

	// 6. Verify User and Credits
	$stmt = $pdo->prepare('SELECT verified, free_credits, purchased_credits FROM users WHERE email = :email AND google_id = :google_id LIMIT 1');
	$stmt->execute([':email' => $email, ':google_id' => $google_id]);
	$user = $stmt->fetch();

	if (!$user || (int)$user['verified'] <= 0) {
		json_response(['error' => true, 'model_response' => 'Unauthorized or unverified user. Please log in again.', 'cost' => 0], 401);
	}

	$free_credits = (int)$user['free_credits'];
	$purchased_credits = (int)$user['purchased_credits'];
	
	// Check dynamic cost
	if (($free_credits + $purchased_credits) < $cost) {
		json_response(['error' => true, 'model_response' => "Insufficient credits. You need at least $cost credits to execute this action.", 'cost' => 0, 'free_credits' => $free_credits, 'purchased_credits' => $purchased_credits], 403);
	}

	// 7. Build the AI System Prompt
	$is_video = count($frames) > 1;
	$system_prompt = "";
	$expect_json = false;
	$user_prompt = "Process the image(s).";

	if ($prompt_id === 'explore') {
		$expect_json = true;
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

	// 8. Execute AI API Call
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
	
	// Set minimum reasoning effort for reasoning models (Grok or GPT-5)
	if ($model_name === 'grok-4.3') {
		$payload["reasoning_effort"] = "low";
	}
	if (strpos(strtolower($model_name), 'gpt-5') !== false) {
		$payload["reasoning_effort"] = "minimal";
	}

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
		if ($debug) {
			echo "<pre>";
			print_r($response);
			echo "</pre>";
		}
		json_response(['error' => true, 'model_response' => 'Error communicating with AI server. Please try again.', 'cost' => 0], 502);
	}

	if ($debug)
		file_put_contents('/home/wikipond/lense_whisper/output.json', $response);
		
	$res_json = json_decode($response, true);
	$model_response = $res_json['choices'][0]['message']['content'] ?? 'Error parsing AI response.';

	// Extract actual model name returned by the API (fallback to intended model)
	$returned_model_name = $res_json['model'] ?? $model_name;

	// 9. Extract Token Usage Stats
	$usage = $res_json['usage'] ?? [];
	$input_tokens = (int)($usage['prompt_tokens'] ?? 0);
	$completion_tokens = (int)($usage['completion_tokens'] ?? 0);
	$reasoning_tokens = (int)($usage['completion_tokens_details']['reasoning_tokens'] ?? 0);
	
	// Combine base completion tokens with reasoning tokens if separated by the API
	$output_tokens = $completion_tokens + $reasoning_tokens;
	$stats_json = empty($usage) ? null : json_encode($usage);

	// 10. Calculate Actual COGS
	$cogs = 0.0;
	if (isset($res_json['cost_in_usd_ticks'])) {
		// Corrected divisor for ticks to 10 Billion
		$cogs = (float)$res_json['cost_in_usd_ticks'] / 10000000000;
	} else {
		$rate_in = 0;
		$rate_out = 0;
		foreach ($token_rates_per_m as $key => $rates) {
			if (strpos(strtolower($returned_model_name), $key) !== false || $model_name === $key) {
				$rate_in = $rates['in'];
				$rate_out = $rates['out'];
				break;
			}
		}
		$cogs = (($input_tokens / 1000000) * $rate_in) + (($output_tokens / 1000000) * $rate_out);
	}

	// 11. Deduct Credits & Log
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
	$updateStmt = $pdo->prepare('UPDATE users SET free_credits = :fc, purchased_credits = :pc, updated_at = CURRENT_TIMESTAMP WHERE email = :email');
	$updateStmt->execute([':fc' => $free_credits, ':pc' => $purchased_credits, ':email' => $email]);

	$logStmt = $pdo->prepare('INSERT INTO purchase_log (email, prompt_id, cost_free, cost_purchased, model_name, input_tokens, output_tokens, stats_json, cogs) VALUES (:email, :prompt_id, :cf, :cp, :model_name, :input_tokens, :output_tokens, :stats_json, :cogs)');
	$logStmt->execute([
		':email' => $email,
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
	
	json_response([
		'error' => false,
		'model_response' => $model_response,
		'cost' => $cost,
		'free_credits' => $free_credits,
		'purchased_credits' => $purchased_credits
	]);

} catch (Exception $e) {
	if (isset($pdo) && $pdo->inTransaction()) { $pdo->rollBack(); }
	if ($debug)
		print_r($pdo->errorInfo());
	json_response(['error' => true, 'model_response' => 'Internal server database error.', 'cost' => 0], 500);
}
?>