# Quick Start Guide - JavaFX File Manager

## Running the Application

To run the application with Java 17:

```powershell
$env:JAVA_HOME = "C:\theta\jdk17"
./gradlew runJfxSpringBootApp
```

Or create a batch file `run.bat`:
```batch
@echo off
set JAVA_HOME=C:\theta\jdk17
gradlew.bat runJfxSpringBootApp
```

## Application Features

### 1. Upload Files
- Click **Browse** button
- Select a `.sql`, `.sqlx`, or `.yaml` file
- Choose a module from dropdown: `bjr`, `cis`, `col`, `lac`, `lms`, `ibdm`
- Select or type a version (defaults: `9.0.0.9`, `9.0.0.10`)
- Click **Upload File**

**File Routing:**
- `.yaml` files → `project/{module}/{version}/`
- `.sql` and `.sqlx` files → `liquibase/{module}/{version}/oracle/`

### 2. View Files
- Files automatically organized in TreeView by Module → Version → Files
- Click **Refresh** to reload the file list

### 3. Delete Files
- Select a file in the tree
- Click **Delete Selected**
- Confirm deletion

### 4. Execute Scripts
- Select a file in the tree
- Click **Execute Script**
- View output and errors in dialog

## Directory Structure

After uploading files, you'll see:

```
f:/WorkspaceExt/ctbctwTools/
├── project/
│   └── bjr/
│       └── 9.0.0.9/
│           └── config.yaml
└── liquibase/
    └── bjr/
        └── 9.0.0.9/
            └── oracle/
                ├── migration.sql
                └── schema.sqlx
```

## Configuration

Edit `src/main/resources/application.properties` to customize:

```properties
# Change upload directories
file.manager.project.directory=./project
file.manager.liquibase.directory=./liquibase

# Add/remove modules
file.manager.modules=bjr,cis,col,lac,lms,ibdm

# Change default versions
file.manager.default.versions=9.0.0.9,9.0.0.10
```

## Troubleshooting

**Application won't start:**
- Ensure Java 17 is at `C:\theta\jdk17`
- Check JAVA_HOME: `$env:JAVA_HOME`
- Rebuild: `./gradlew clean build`

**Files not appearing:**
- Click **Refresh** button
- Check that files have correct extensions (`.yaml`, `.sql`, `.sqlx`)
- Verify module and version were selected during upload

**Script execution fails:**
- Check file permissions
- Verify file path is correct
- Review error output in the execution dialog
