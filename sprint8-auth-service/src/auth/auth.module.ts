import { Module } from '@nestjs/common';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './auth.guard';
import { LoginThrottleService } from './login-throttle.service';
import { TokensModule } from '../tokens/tokens.module';
import { CryptoModule } from '../crypto/crypto.module';

@Module({
  imports: [TokensModule, CryptoModule],
  controllers: [AuthController],
  providers: [AuthService, LoginThrottleService, JwtAuthGuard],
  exports: [AuthService],
})
export class AuthModule {}