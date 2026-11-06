<?php
// routes/api.php  ->  URLs become /api/device/sms/next and /api/device/sms/{id}/result
//
// 1. php artisan make:model SmsMessage   (set: protected $guarded = [];)
// 2. Put SMS_DEVICE_TOKEN in .env and expose it in config/sms.php:
//        return ['device_token' => env('SMS_DEVICE_TOKEN')];
// 3. In the Android app:
//        Poll URL   https://yourdomain.com/api/device/sms/next
//        Result URL https://yourdomain.com/api/device/sms/{id}/result
//        Auth       Bearer token  (same value as SMS_DEVICE_TOKEN)

use App\Models\SmsMessage;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Route;

Route::prefix('device/sms')->middleware('throttle:120,1')->group(function () {

    $authorize = function (Request $r) {
        abort_unless(hash_equals((string) config('sms.device_token'), (string) $r->bearerToken()), 401);
    };

    // The phone asks for the next pending message. 204 = nothing to send.
    Route::get('next', function (Request $r) use ($authorize) {
        $authorize($r);

        return DB::transaction(function () {
            $m = SmsMessage::where('status', 'pending')->oldest()->lockForUpdate()->first();
            if (!$m) {
                return response()->noContent();
            }
            $m->update(['status' => 'sending', 'claimed_at' => now()]);

            return response()->json(['id' => (string) $m->id, 'to' => $m->to, 'body' => $m->body]);
        });
    });

    // The phone reports: status = sent | delivered | failed
    Route::post('{message}/result', function (Request $r, SmsMessage $message) use ($authorize) {
        $authorize($r);

        $status = $r->input('status');
        abort_unless(in_array($status, ['sent', 'delivered', 'failed'], true), 422);

        // "delivered" is final; ignore late duplicates.
        if ($message->status === 'delivered') {
            return ['ok' => true];
        }

        $message->update([
            'status'  => $status,
            'sent_at' => $message->sent_at ?? (in_array($status, ['sent', 'delivered']) ? now() : null),
            'error'   => $status === 'failed' ? substr((string) $r->input('error'), 0, 500) : null,
        ]);

        return ['ok' => true];
    });
});

// Queue a message from anywhere in your app:
//   SmsMessage::create(['to' => '+923001234567', 'body' => 'Your code is 4821']);
//
// Recommended: a scheduled task that fails messages stuck in "sending" (phone died mid-send)
// and expires old "pending" ones so stale OTPs are never sent late.
