import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import {
  IsEmail,
  IsNotEmpty,
  IsOptional,
  IsString,
  Matches,
  MaxLength,
  MinLength,
} from 'class-validator';

export class RegisterDto {
  @ApiProperty({ example: 'Priya' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(100)
  firstName!: string;

  @ApiPropertyOptional({ example: 'K' })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  middleName?: string;

  @ApiProperty({ example: 'Menon' })
  @IsString()
  @IsNotEmpty()
  @MaxLength(100)
  lastName!: string;

  @ApiProperty({ example: 'priya.menon' })
  @IsString()
  @MinLength(3)
  @MaxLength(64)
  @Matches(/^[a-zA-Z0-9._-]+$/, {
    message: 'username may only contain letters, numbers, dot, dash and underscore',
  })
  username!: string;

  @ApiProperty({ example: 'priya.menon@example.com' })
  @IsEmail()
  @MaxLength(255)
  email!: string;

  @ApiProperty({
    example: 'Capstone@2026',
    description:
      'Minimum twelve characters, length-only policy per the contract: length beats ' +
      'character-class rules, so no symbol/upper/lower/digit requirements are imposed.',
  })
  @IsString()
  @MinLength(12, { message: 'password must be at least 12 characters long' })
  @MaxLength(128, { message: 'password must be at most 128 characters long' })
  password!: string;

  @ApiProperty({ example: 'Capstone@2026' })
  @IsString()
  @IsNotEmpty()
  confirmPassword!: string;
}