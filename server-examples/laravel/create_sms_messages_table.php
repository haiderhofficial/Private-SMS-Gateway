<?php
// database/migrations/xxxx_create_sms_messages_table.php
use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void
    {
        Schema::create('sms_messages', function (Blueprint $t) {
            $t->id();
            $t->string('to');
            $t->text('body');
            $t->string('status')->default('pending'); // pending, sending, sent, delivered, failed
            $t->text('error')->nullable();
            $t->timestamp('claimed_at')->nullable();
            $t->timestamp('sent_at')->nullable();
            $t->timestamps();
            $t->index(['status', 'created_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sms_messages');
    }
};
