from glob import glob

from setuptools import find_packages, setup

package_name = 'android_bridge'

setup(
    name=package_name,
    version='0.1.0',
    packages=find_packages(exclude=['test']),
    data_files=[
        ('share/ament_index/resource_index/packages', ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
        ('share/' + package_name + '/config', glob('config/*.yaml')),
        ('share/' + package_name + '/launch', glob('launch/*.launch.py')),
    ],
    install_requires=['setuptools'],
    zip_safe=True,
    maintainer='Lorenzo Oliveira',
    maintainer_email='lorenzo.oliveira@ee.ufcg.edu.br',
    description='ROS 2 bridge for the androsid Android sensor streamer.',
    license='Apache-2.0',
    entry_points={
        'console_scripts': [
            'android_sensor = android_bridge.android_sensor:main',
            'android_actuator = android_bridge.android_actuator:main',
        ],
    },
    tests_require=['pytest'],
)
